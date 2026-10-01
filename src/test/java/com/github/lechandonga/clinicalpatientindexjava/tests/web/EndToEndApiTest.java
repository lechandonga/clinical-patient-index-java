package com.github.lechandonga.clinicalpatientindexjava.tests.web;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndApiTest {

    private static final Logger log = LoggerFactory.getLogger(EndToEndApiTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static final String HL7_MESSAGE = """
            MSH|^~\\&|HIS_A|HOSPITAL_A|MPI|MPI|20261001120000||ADT^A04^ADT_A01|MSG100|P|2.5.1
            PID|1||MRN-7001^^^HOSPITAL_A^MR||Zhao^Min||19880320|F
            """;

    private static final String FHIR_MESSAGE = """
            {
              "resourceType": "Patient",
              "identifier": [
                {"system": "HOSPITAL_B", "value": "7002"},
                {"system": "HOSPITAL_A", "value": "MRN7001"}
              ],
              "name": [{"use": "official", "family": "Zhao", "given": ["Min"]}],
              "gender": "female",
              "birthDate": "1988-03-20"
            }
            """;

    @Test
    void endToEndIngestMergeDeidentifyAuditAndAuthorize() {
        HttpHeaders hl7Headers = new HttpHeaders();
        hl7Headers.setBearerAuth("ingest-token-a");
        hl7Headers.setContentType(MediaType.TEXT_PLAIN);
        log.info("[TEST INPUT] HL7 ingest from source-a");
        ResponseEntity<String> hl7Resp = rest.exchange(url("/api/patients/ingest"),
                HttpMethod.POST, new HttpEntity<>(HL7_MESSAGE, hl7Headers), String.class);
        log.info("[TEST BASIS] HL7 ingest status={} body={}", hl7Resp.getStatusCode(), hl7Resp.getBody());
        assertEquals(200, hl7Resp.getStatusCode().value());
        assertTrue(hl7Resp.getBody().contains("\"created\":true"));

        HttpHeaders fhirHeaders = new HttpHeaders();
        fhirHeaders.setBearerAuth("ingest-token-b");
        fhirHeaders.setContentType(MediaType.APPLICATION_JSON);
        log.info("[TEST INPUT] FHIR ingest from source-b sharing normalized id MRN7001");
        ResponseEntity<String> fhirResp = rest.exchange(url("/api/patients/ingest"),
                HttpMethod.POST, new HttpEntity<>(FHIR_MESSAGE, fhirHeaders), String.class);
        log.info("[TEST BASIS] FHIR ingest status={} body={}", fhirResp.getStatusCode(), fhirResp.getBody());
        assertEquals(200, fhirResp.getStatusCode().value());
        String mpiId = jsonField(fhirResp.getBody(), "mpiId");
        assertFalse(fhirResp.getBody().contains("\"created\":true"),
                "cross-source match must merge into existing MPI");
        assertNotNull(mpiId);

        HttpHeaders queryHeaders = new HttpHeaders();
        queryHeaders.setBearerAuth("query-token");
        ResponseEntity<String> view1 = rest.exchange(
                url("/api/patients?system=hospital_a&identifier=mrn-7001"),
                HttpMethod.GET, new HttpEntity<>(queryHeaders), String.class);
        ResponseEntity<String> view2 = rest.exchange(
                url("/api/patients?system=HOSPITAL_B&identifier=MRN-7002"),
                HttpMethod.GET, new HttpEntity<>(queryHeaders), String.class);
        log.info("[TEST INPUT] two de-identified queries via different source ids");
        log.info("[TEST BASIS] view1={} view2={}", view1.getBody(), view2.getBody());
        assertEquals(200, view1.getStatusCode().value());
        assertEquals(200, view2.getStatusCode().value());
        assertEquals(jsonField(view1.getBody(), "pseudonym"),
                jsonField(view2.getBody(), "pseudonym"),
                "pseudonym must be stable for the same patient");
        for (String body : new String[]{view1.getBody(), view2.getBody()}) {
            assertFalse(body.contains("Zhao"), "response must not contain family name");
            assertFalse(body.contains("Min"), "response must not contain given name");
            assertFalse(body.contains("7001"), "response must not contain raw identifier");
            assertTrue(body.contains("\"birthYear\":1988"));
            assertTrue(body.contains("PSEU-"));
        }

        log.info("[TEST INPUT] unauthenticated query (no token)");
        ResponseEntity<String> noToken = rest.exchange(
                url("/api/patients?system=HOSPITAL_A&identifier=MRN7001"),
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        log.info("[TEST BASIS] no-token status={} body={}", noToken.getStatusCode(), noToken.getBody());
        assertEquals(401, noToken.getStatusCode().value());
        assertTrue(noToken.getBody().contains("MISSING_TOKEN"));

        HttpHeaders badTokenHeaders = new HttpHeaders();
        badTokenHeaders.setBearerAuth("does-not-exist");
        ResponseEntity<String> badToken = rest.exchange(
                url("/api/patients?system=HOSPITAL_A&identifier=MRN7001"),
                HttpMethod.GET, new HttpEntity<>(badTokenHeaders), String.class);
        log.info("[TEST INPUT] invalid token -> [TEST BASIS] status={} body={}",
                badToken.getStatusCode(), badToken.getBody());
        assertEquals(401, badToken.getStatusCode().value());
        assertTrue(badToken.getBody().contains("INVALID_TOKEN"));

        HttpHeaders ingestOnly = new HttpHeaders();
        ingestOnly.setBearerAuth("ingest-token-a");
        ResponseEntity<String> forbidden = rest.exchange(
                url("/api/patients?system=HOSPITAL_A&identifier=MRN7001"),
                HttpMethod.GET, new HttpEntity<>(ingestOnly), String.class);
        log.info("[TEST INPUT] ingest-only token queries -> [TEST BASIS] status={} body={}",
                forbidden.getStatusCode(), forbidden.getBody());
        assertEquals(403, forbidden.getStatusCode().value());
        assertTrue(forbidden.getBody().contains("FORBIDDEN_SCOPE"));

        ResponseEntity<String> queryHitsAudit = rest.exchange(url("/api/audit/events"),
                HttpMethod.GET, new HttpEntity<>(queryHeaders), String.class);
        assertEquals(403, queryHitsAudit.getStatusCode().value());

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth("admin-token");
        ResponseEntity<String> audit = rest.exchange(url("/api/audit/events"),
                HttpMethod.GET, new HttpEntity<>(adminHeaders), String.class);
        log.info("[TEST INPUT] admin reads audit trail");
        log.info("[TEST BASIS] audit status={}, events-length={}",
                audit.getStatusCode(), audit.getBody().length());
        assertEquals(200, audit.getStatusCode().value());
        assertFalse(audit.getBody().contains("Zhao"), "audit must not contain plaintext family name");
        assertFalse(audit.getBody().contains("7001"), "audit must not contain plaintext identifiers");
        assertTrue(audit.getBody().contains("INGEST"));
        assertTrue(audit.getBody().contains("QUERY"));
        assertTrue(audit.getBody().contains("source-a"));
    }

    private static String jsonField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            return null;
        }
        start += key.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
