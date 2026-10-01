package com.github.lechandonga.clinicalpatientindexjava.tests.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.clinicalpatientindexjava.audit.AuditService;
import com.github.lechandonga.clinicalpatientindexjava.mpi.MasterPatientIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(ApiEndToEndTest.class);

    private static final String HL7_A01 = """
            MSH|^~\\&|HIS_APP|HIS|LAB_APP|LIS|20261001120000||ADT^A04|MSG00001|P|2.5.1
            EVN|A04|20261001120000
            PID|1||MRN-00012345^^^HIS^MR||张^三||19840307|M|||朝阳区^^北京||13800000000
            """;

    private static final String FHIR_PATIENT = """
            {
              "resourceType": "Patient",
              "identifier": [
                {"system": "EMR", "value": "mrn00012345", "type": {"coding": [{"code": "MR"}]}},
                {"system": "LIS", "value": "P-777"}
              ],
              "name": [{"use": "official", "family": "张", "given": ["三"]}],
              "gender": "male",
              "birthDate": "1984-03-07",
              "telecom": [{"system": "phone", "value": "13800000000"}]
            }
            """;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MasterPatientIndex index;
    @Autowired
    private AuditService auditService;

    @BeforeEach
    void resetState() {
        index.reset();
        auditService.reset();
    }

    @Test
    void malformedInputsReturnDistinctErrorCodes() throws Exception {
        expectFailure("not a message at all", "FORMAT_ERROR");
        expectFailure("{\"resourceType\":\"Patient\", broken", "FORMAT_ERROR");
        expectFailure(HL7_A01.replace("2.5.1", "9.9"), "UNSUPPORTED_VERSION");
        expectFailure(HL7_A01.replace("19840307", "19840230"), "TIME_FORMAT_ERROR");
        expectFailure(HL7_A01.replace("MRN-00012345^^^HIS^MR", "^^^HIS"), "IDENTIFIER_INVALID");
        expectFailure(HL7_A01.replace("PID|1||MRN-00012345^^^HIS^MR||张^三|",
                "PID|1||MRN-00012345^^^HIS^MR|||"), "MISSING_REQUIRED_FIELD");
        log.info("畸形输入判定覆盖 FORMAT_ERROR / UNSUPPORTED_VERSION / TIME_FORMAT_ERROR / IDENTIFIER_INVALID / MISSING_REQUIRED_FIELD");
    }

    private void expectFailure(String body, String expectedCode) throws Exception {
        boolean json = body.stripLeading().startsWith("{");
        mockMvc.perform(post("/api/ingest/hl7")
                        .header("X-Api-Key", "ingest-his-key")
                        .contentType(json ? MediaType.APPLICATION_JSON : MediaType.TEXT_PLAIN)
                        .content(body))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.errorCode").value(expectedCode));
        log.info("输入摘要 sha256:16={} 长度={} -> 判定 {}",
                com.github.lechandonga.clinicalpatientindexjava.deid.DeIdentificationService.fingerprint(body),
                body.length(), expectedCode);
    }

    @Test
    void crossFormatSubmissionsMergeToSingleStableMasterIndex() throws Exception {
        String hl7Response = ingest(HL7_A01, "ingest-his-key", "/api/ingest/hl7", MediaType.TEXT_PLAIN);
        String fhirResponse = ingest(FHIR_PATIENT, "admin-key", "/api/ingest/fhir", MediaType.APPLICATION_JSON);
        JsonNode first = objectMapper.readTree(hl7Response);
        JsonNode second = objectMapper.readTree(fhirResponse);
        assertEquals(first.get("masterId").asText(), second.get("masterId").asText(),
                "HL7 与 FHIR 中同一患者必须归并到相同主索引");
        assertEquals(false, second.get("created").asBoolean());
        log.info("跨格式归并判定: HIS/MRN-00012345 两种表示 -> masterId={}", first.get("masterId").asText());
    }

    @Test
    void deidentifiedQueryIsStableAndFreeOfDirectIdentifiers() throws Exception {
        ingest(HL7_A01, "ingest-his-key", "/api/ingest/hl7", MediaType.TEXT_PLAIN);

        String byHis = query("HIS", "MRN 00012345");
        String byEmr = query("emr", "mrn-00012345");
        JsonNode first = objectMapper.readTree(byHis);
        JsonNode second = objectMapper.readTree(byEmr);
        assertEquals(first.get("pseudonym").asText(), second.get("pseudonym").asText(),
                "同一患者在不同查询中的伪名必须稳定一致");
        assertEquals("1984", first.get("birthYear").asText(), "出生日期必须泛化为出生年份");

        String raw = byHis + byEmr;
        assertFalse(raw.contains("张"), "去标识化输出不得含姓名");
        assertFalse(raw.contains("13800000000"), "去标识化输出不得含联系方式");
        assertFalse(raw.contains("00012345"), "去标识化输出不得含原始标识符");
        log.info("去标识化判定 pseudonym={} 稳定，直接标识检查全部通过（无姓名/电话/原始号）",
                first.get("pseudonym").asText());
    }

    @Test
    void unauthorizedAndOutOfScopeAccessAreRejectedWithReasons() throws Exception {
        ingest(HL7_A01, "ingest-his-key", "/api/ingest/hl7", MediaType.TEXT_PLAIN);

        mockMvc.perform(get("/api/patients/find").param("system", "HIS").param("identifier", "MRN-00012345"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/api/patients/find")
                        .header("X-Api-Key", "ingest-his-key")
                        .param("system", "HIS").param("identifier", "MRN-00012345"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ROLE"));

        mockMvc.perform(post("/api/ingest/hl7")
                        .header("X-Api-Key", "ingest-lab-key")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(HL7_A01))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_SCOPE"));

        mockMvc.perform(get("/api/audit/events").header("X-Api-Key", "query-key"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ROLE"));
        log.info("权限判定: 无Key=UNAUTHENTICATED, 缺角色=FORBIDDEN_ROLE, 跨系统=FORBIDDEN_SCOPE");
    }

    @Test
    void auditTrailTracksAccessAndContainsNoSensitivePlaintext() throws Exception {
        ingest(HL7_A01, "ingest-his-key", "/api/ingest/hl7", MediaType.TEXT_PLAIN);
        query("HIS", "MRN-00012345");
        mockMvc.perform(get("/api/patients/find")
                        .header("X-Api-Key", "query-key")
                        .param("system", "HIS").param("identifier", "NO-SUCH"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PATIENT_NOT_FOUND"));

        String auditBody = mockMvc.perform(get("/api/audit/events").header("X-Api-Key", "audit-key"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode events = objectMapper.readTree(auditBody);
        assertEquals(true, events.isArray());
        assertFalse(auditBody.contains("张"), "审计日志不得含姓名明文");
        assertFalse(auditBody.contains("13800000000"), "审计日志不得含联系方式明文");
        assertFalse(auditBody.contains("00012345"), "审计日志不得含原始标识符明文");
        assertEquals(true, auditBody.contains("INGEST"));
        assertEquals(true, auditBody.contains("QUERY"));
        assertEquals(true, auditBody.contains("DENIED"));
        log.info("审计判定: 事件数={}，含 INGEST/QUERY/DENIED，且无姓名/电话/原始号明文", events.size());
    }

    private String ingest(String body, String apiKey, String path, MediaType contentType) throws Exception {
        return mockMvc.perform(post(path).header("X-Api-Key", apiKey)
                        .contentType(contentType).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String query(String system, String identifier) throws Exception {
        return mockMvc.perform(get("/api/patients/find")
                        .header("X-Api-Key", "query-key")
                        .param("system", system)
                        .param("identifier", identifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
