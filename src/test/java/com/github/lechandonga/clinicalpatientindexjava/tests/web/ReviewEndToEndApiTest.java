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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新流程的 HTTP 端到端覆盖：
 * 预检只读、冲突对外表现不变 + 待办生成、待办去重、越权三态区分、
 * 裁决幂等、批准后标识归一、驳回不变、审计无明文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReviewEndToEndApiTest {

    private static final Logger log = LoggerFactory.getLogger(ReviewEndToEndApiTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.TEXT_PLAIN);
        return headers;
    }

    private static String hl7(String id, String family, String birthDate) {
        return "MSH|^~\\&|HIS_A|HOSPITAL_A|MPI|MPI|20261001120000||ADT^A04^ADT_A01|MSG"
                + id + "|P|2.5.1\n"
                + "PID|1||" + id + "^^^HOSPITAL_A^MR||" + family + "^^||" + birthDate + "|F\n";
    }

    private ResponseEntity<String> ingest(String body, String token) {
        return rest.exchange(url("/api/patients/ingest"), HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), String.class);
    }

    @Test
    void previewConflictReviewAndDecisionEndToEnd() {
        // 两个独立患者（不同系统、不同号、不同生日）。
        assertEquals(200, ingest(hl7("MRN-8001", "Zhao", "19880320"),
                "ingest-token-a").getStatusCode().value());
        String fhirB = "{\"resourceType\":\"Patient\","
                + "\"identifier\":[{\"system\":\"HOSPITAL_B\",\"value\":\"MRN-8002\"}],"
                + "\"name\":[{\"family\":\"Qian\"}],"
                + "\"gender\":\"female\",\"birthDate\":\"1977-11-05\"}";
        HttpHeaders fhirHeaders = auth("ingest-token-b");
        fhirHeaders.setContentType(MediaType.APPLICATION_JSON);
        assertEquals(200, rest.exchange(url("/api/patients/ingest"), HttpMethod.POST,
                new HttpEntity<>(fhirB, fhirHeaders), String.class).getStatusCode().value());

        String conflicting = "MSH|^~\\&|HIS_A|HOSPITAL_A|MPI|MPI|20261001120000||ADT^A04^ADT_A01|MSGC|P|2.5.1\n"
                + "PID|1||MRN-8001^^^HOSPITAL_A^MR~MRN-8002^^^HOSPITAL_B^MR||Qian^^||19771105|F\n";

        // 预检：同样能看出会被冲突拒绝，且不产生任何变化。
        ResponseEntity<String> previewConflict = rest.exchange(url("/api/patients/preview"),
                HttpMethod.POST, new HttpEntity<>(conflicting, auth("ingest-token-a")),
                String.class);
        log.info("[TEST INPUT] preview conflicting HL7 -> [TEST BASIS] status={} body={}",
                previewConflict.getStatusCode(), previewConflict.getBody());
        assertEquals(409, previewConflict.getStatusCode().value());
        assertTrue(previewConflict.getBody().contains("CONTRADICTORY_DATA"));

        // 预检一个正常新患者：可读归一结果与字段效果，但无姓名/原始标识/出生日明文。
        ResponseEntity<String> previewNew = rest.exchange(url("/api/patients/preview"),
                HttpMethod.POST,
                new HttpEntity<>(hl7("MRN-8003", "Sun", "19920505"), auth("ingest-token-a")),
                String.class);
        log.info("[TEST INPUT] preview new HL7 -> [TEST BASIS] status={} body={}",
                previewNew.getStatusCode(), previewNew.getBody());
        assertEquals(200, previewNew.getStatusCode().value());
        assertTrue(previewNew.getBody().contains("\"wouldCreate\":true"));
        assertTrue(previewNew.getBody().contains("\"birthYear\":1992"));
        assertFalse(previewNew.getBody().contains("Sun"), "preview must hide names");
        assertFalse(previewNew.getBody().contains("8003"), "preview must hide raw ids");
        assertFalse(previewNew.getBody().contains("1992-05-05"),
                "preview must not expose full birth date");

        // 正式提交冲突：对外仍是 409 CONTRADICTORY_DATA（老接入方无感）。
        ResponseEntity<String> formal = ingest(conflicting, "ingest-token-a");
        log.info("[TEST INPUT] formal conflicting ingest -> [TEST BASIS] status={} body={}",
                formal.getStatusCode(), formal.getBody());
        assertEquals(409, formal.getStatusCode().value());
        assertTrue(formal.getBody().contains("CONTRADICTORY_DATA"));

        // 重复提交同冲突 -> 仍然 409 CONTRADICTORY_DATA，待办不增加。
        assertEquals(409, ingest(conflicting, "ingest-token-b").getStatusCode().value());

        // 越权三态。
        ResponseEntity<String> noToken = rest.exchange(url("/api/reviews"),
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        assertEquals(401, noToken.getStatusCode().value());
        assertTrue(noToken.getBody().contains("MISSING_TOKEN"));

        HttpHeaders bad = new HttpHeaders();
        bad.setBearerAuth("nope");
        ResponseEntity<String> invalidToken = rest.exchange(url("/api/reviews"),
                HttpMethod.GET, new HttpEntity<>(bad), String.class);
        assertEquals(401, invalidToken.getStatusCode().value());
        assertTrue(invalidToken.getBody().contains("INVALID_TOKEN"));

        ResponseEntity<String> forbidden = rest.exchange(url("/api/reviews"),
                HttpMethod.GET, new HttpEntity<>(auth("query-token")), String.class);
        assertEquals(403, forbidden.getStatusCode().value());
        assertTrue(forbidden.getBody().contains("FORBIDDEN_SCOPE"));
        log.info("[TEST BASIS] review auth failures distinguished: {}, {}, {}",
                noToken.getBody(), invalidToken.getBody(), forbidden.getBody());

        // 复核员看待办：只有一条（去重），内容仅含替代标识/哈希。
        ResponseEntity<String> reviews = rest.exchange(url("/api/reviews"),
                HttpMethod.GET, new HttpEntity<>(auth("reviewer-token")), String.class);
        log.info("[TEST INPUT] reviewer lists open tasks -> [TEST BASIS] body={}",
                reviews.getBody());
        assertEquals(200, reviews.getStatusCode().value());
        assertTrue(reviews.getBody().contains("PSEU-"));
        assertFalse(reviews.getBody().contains("Zhao"));
        assertFalse(reviews.getBody().contains("8001"));
        assertFalse(reviews.getBody().contains("1988") && reviews.getBody().contains("1977"),
                "review task must not contain birth dates");
        assertTrue(reviews.getBody().contains("BIRTH_DATE"));
        String reviewId = jsonField(reviews.getBody(), "id");

        // 先驳回，再批准 -> 已决，返回相同驳回结果，数据保持两个独立 MPI。
        HttpHeaders reviewer = auth("reviewer-token");
        reviewer.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> reject = rest.exchange(
                url("/api/reviews/" + reviewId + "/decision"), HttpMethod.POST,
                new HttpEntity<>("{\"decision\":\"REJECT\"}", reviewer), String.class);
        assertTrue(reject.getStatusCode().is2xxSuccessful());
        assertTrue(reject.getBody().contains("REJECTED"));
        ResponseEntity<String> approveAfterReject = rest.exchange(
                url("/api/reviews/" + reviewId + "/decision"), HttpMethod.POST,
                new HttpEntity<>("{\"decision\":\"APPROVE\"}", reviewer), String.class);
        log.info("[TEST BASIS] opposite decision after rejection status={} body={}",
                approveAfterReject.getStatusCode(), approveAfterReject.getBody());
        assertEquals(409, approveAfterReject.getStatusCode().value());
        assertTrue(approveAfterReject.getBody().contains("REVIEW_ALREADY_DECIDED"));

        // 再次制造同一冲突并批准。
        ResponseEntity<String> formal2 = ingest(conflicting, "ingest-token-a");
        assertEquals(409, formal2.getStatusCode().value());
        ResponseEntity<String> reviews2 = rest.exchange(url("/api/reviews"),
                HttpMethod.GET, new HttpEntity<>(auth("reviewer-token")), String.class);
        String reviewId2 = jsonField(reviews2.getBody(), "id");
        ResponseEntity<String> approve = rest.exchange(
                url("/api/reviews/" + reviewId2 + "/decision"), HttpMethod.POST,
                new HttpEntity<>("{\"decision\":\"APPROVE\"}", reviewer), String.class);
        log.info("[TEST INPUT] reviewer approves -> [TEST BASIS] body={}", approve.getBody());
        assertEquals(200, approve.getStatusCode().value());
        assertTrue(approve.getBody().contains("APPROVED"));

        // 重复批准：幂等返回，不二次生效。
        ResponseEntity<String> approveRepeat = rest.exchange(
                url("/api/reviews/" + reviewId2 + "/decision"), HttpMethod.POST,
                new HttpEntity<>("{\"decision\":\"APPROVE\"}", reviewer), String.class);
        assertEquals(200, approveRepeat.getStatusCode().value());
        assertTrue(approveRepeat.getBody().contains("\"applied\":false"));
        assertTrue(approveRepeat.getBody().contains("APPROVED"));

        // 两边标识最终归一到同一主索引。
        HttpHeaders queryHeaders = new HttpHeaders();
        queryHeaders.setBearerAuth("query-token");
        ResponseEntity<String> v1 = rest.exchange(
                url("/api/patients?system=hospital_a&identifier=mrn-8001"),
                HttpMethod.GET, new HttpEntity<>(queryHeaders), String.class);
        ResponseEntity<String> v2 = rest.exchange(
                url("/api/patients?system=hospital_b&identifier=MRN8002"),
                HttpMethod.GET, new HttpEntity<>(queryHeaders), String.class);
        assertEquals(200, v1.getStatusCode().value());
        assertEquals(200, v2.getStatusCode().value());
        assertEquals(jsonField(v1.getBody(), "pseudonym"),
                jsonField(v2.getBody(), "pseudonym"),
                "both identifiers must resolve to the same merged patient");

        // 审计：admin 可读，且新流程事件齐全、无明文。
        ResponseEntity<String> audit = rest.exchange(url("/api/audit/events"),
                HttpMethod.GET, new HttpEntity<>(auth("admin-token")), String.class);
        assertEquals(200, audit.getStatusCode().value());
        String body = audit.getBody();
        assertTrue(body.contains("PREVIEW"));
        assertTrue(body.contains("INGEST_CONFLICT"));
        assertTrue(body.contains("REVIEW_DECIDE"));
        assertFalse(body.contains("Zhao"), "audit must not contain plaintext family name");
        assertFalse(body.contains("8001"), "audit must not contain raw identifiers");
        assertFalse(body.contains("19880320"), "audit must not contain plaintext birth dates");
        log.info("[TEST BASIS] audit covers new flows and contains no plaintext (length={})",
                body.length());
    }

    @Test
    void previewRequiresIngestCredential() {
        ResponseEntity<String> noToken = rest.exchange(url("/api/patients/preview"),
                HttpMethod.POST,
                new HttpEntity<>(hl7("MRN-9010", "Li", "19900101"), new HttpHeaders()),
                String.class);
        assertEquals(401, noToken.getStatusCode().value());
        assertTrue(noToken.getBody().contains("MISSING_TOKEN"));

        ResponseEntity<String> reviewer = rest.exchange(url("/api/patients/preview"),
                HttpMethod.POST,
                new HttpEntity<>(hl7("MRN-9010", "Li", "19900101"),
                        auth("reviewer-token")), String.class);
        assertEquals(403, reviewer.getStatusCode().value());
        assertTrue(reviewer.getBody().contains("FORBIDDEN_SCOPE"));
        log.info("[TEST BASIS] preview credential failures: {} / {}",
                noToken.getBody(), reviewer.getBody());
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
