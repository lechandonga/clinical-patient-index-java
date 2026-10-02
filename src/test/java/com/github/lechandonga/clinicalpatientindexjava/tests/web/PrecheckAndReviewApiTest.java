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

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditLog;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewQueue;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mpi.review.max-pending=2")
class PrecheckAndReviewApiTest {

    private static final Logger log = LoggerFactory.getLogger(PrecheckAndReviewApiTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MasterPatientIndex index;

    @Autowired
    ReviewQueue reviewQueue;

    @Autowired
    AuditLog auditLog;

    @BeforeEach
    void resetState() {
        index.resetForTesting();
        reviewQueue.resetForTesting();
        auditLog.resetForTesting();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static final String PATIENT_A = """
            MSH|^~\\&|HIS_A|HOSPITAL_A|MPI|MPI|20261001120000||ADT^A04^ADT_A01|MSG-A|P|2.5.1
            PID|1||IDAAA^^^HOSPITAL_A^MR||Zhao^Min||19880320|F
            """;

    private static final String PATIENT_B = """
            {
              "resourceType": "Patient",
              "identifier": [{"system": "HOSPITAL_B", "value": "IDBBB"}],
              "name": [{"family": "Wang", "given": ["Er"]}],
              "gender": "male",
              "birthDate": "1972-11-11"
            }
            """;

    private static final String BRIDGE_CONFLICT = """
            {
              "resourceType": "Patient",
              "identifier": [
                {"system": "HOSPITAL_A", "value": "IDAAA"},
                {"system": "HOSPITAL_B", "value": "IDBBB"}
              ],
              "name": [{"family": "Zhao"}],
              "birthDate": "1988-03-20"
            }
            """;

    private static final String MALFORMED = "{not-json";

    private HttpHeaders jsonWith(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders hl7With(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.TEXT_PLAIN);
        return h;
    }

    private ResponseEntity<String> post(String path, String body, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
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

    @Test
    void precheckIsReadOnlyAndUsesSameRejectionReasonsAsIngest() {
        ResponseEntity<String> preReject = post("/api/patients/precheck", MALFORMED,
                jsonWith("ingest-token-a"));
        log.info("[TEST INPUT] precheck malformed FHIR -> [TEST BASIS] {} {}",
                preReject.getStatusCode(), preReject.getBody());
        assertEquals(200, preReject.getStatusCode().value());
        assertTrue(preReject.getBody().contains("\"acceptable\":false"));
        assertTrue(preReject.getBody().contains("MALFORMED_MESSAGE"));
        ResponseEntity<String> formalReject = post("/api/patients/ingest", MALFORMED,
                jsonWith("ingest-token-a"));
        assertEquals(400, formalReject.getStatusCode().value());
        assertTrue(formalReject.getBody().contains("MALFORMED_MESSAGE"));

        ResponseEntity<String> preA = post("/api/patients/precheck", PATIENT_A,
                hl7With("ingest-token-a"));
        log.info("[TEST BASIS] precheck new HL7 outcome body={}", preA.getBody());
        assertTrue(preA.getBody().contains("\"acceptable\":true"));
        assertTrue(preA.getBody().contains("CREATE_NEW"));
        assertEquals(404, queryId("HOSPITAL_A", "IDAAA").getStatusCode().value(),
                "precheck must not create the patient");

        ResponseEntity<String> created = post("/api/patients/ingest", PATIENT_A,
                hl7With("ingest-token-a"));
        assertEquals(200, created.getStatusCode().value());
        post("/api/patients/ingest", PATIENT_B, jsonWith("ingest-token-b"));

        String pseuA = jsonField(queryId("HOSPITAL_A", "IDAAA").getBody(), "pseudonym");
        String pseuB = jsonField(queryId("HOSPITAL_B", "IDBBB").getBody(), "pseudonym");

        ResponseEntity<String> preConflict = post("/api/patients/precheck", BRIDGE_CONFLICT,
                jsonWith("ingest-token-b"));
        log.info("[TEST BASIS] precheck conflict body={}", preConflict.getBody());
        assertTrue(preConflict.getBody().contains("\"acceptable\":false"));
        assertTrue(preConflict.getBody().contains("CONFLICT_REVIEW"));
        assertTrue(preConflict.getBody().contains("BIRTH_DATE"));
        assertTrue(preConflict.getBody().contains(pseuA));
        assertTrue(preConflict.getBody().contains(pseuB));
        assertFalse(preConflict.getBody().contains("MPI-"),
                "precheck must reference existing patients only by stable pseudonyms");
        assertFalse(preConflict.getBody().contains("IDAAA"));
        assertFalse(preConflict.getBody().contains("IDBBB"));

        ResponseEntity<String> preConflictAgain = post("/api/patients/precheck", BRIDGE_CONFLICT,
                jsonWith("ingest-token-b"));
        assertEquals(preConflict.getBody(), preConflictAgain.getBody(),
                "repeated precheck must return identical result");
        assertEquals(pseuA, jsonField(queryId("HOSPITAL_A", "IDAAA").getBody(), "pseudonym"));
        assertEquals(pseuB, jsonField(queryId("HOSPITAL_B", "IDBBB").getBody(), "pseudonym"),
                "conflict precheck must leave both indices separate and unchanged");

        ResponseEntity<String> preUpdate = post("/api/patients/precheck", PATIENT_A,
                hl7With("ingest-token-a"));
        assertTrue(preUpdate.getBody().contains("UPDATE_EXISTING"));
        assertTrue(preUpdate.getBody().contains(pseuA));
        assertTrue(preUpdate.getBody().contains("KEEP_EXISTING"));
    }

    private ResponseEntity<String> queryId(String system, String value) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("query-token");
        return rest.exchange(url("/api/patients?system=" + system + "&identifier=" + value),
                HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    @Test
    void conflictIngestStaysExternallyRejectedButCreatesDeduplicatedTicket() {
        post("/api/patients/ingest", PATIENT_A, hl7With("ingest-token-a"));
        post("/api/patients/ingest", PATIENT_B, jsonWith("ingest-token-b"));
        String pseuA = jsonField(queryId("HOSPITAL_A", "IDAAA").getBody(), "pseudonym");
        String pseuB = jsonField(queryId("HOSPITAL_B", "IDBBB").getBody(), "pseudonym");

        ResponseEntity<String> conflict = post("/api/patients/ingest", BRIDGE_CONFLICT,
                jsonWith("ingest-token-b"));
        log.info("[TEST INPUT] formal conflicting submit -> [TEST BASIS] {} {}",
                conflict.getStatusCode(), conflict.getBody());
        assertEquals(409, conflict.getStatusCode().value());
        assertTrue(conflict.getBody().contains("CONTRADICTORY_DATA"),
                "external behavior must stay identical for legacy senders");
        assertEquals(pseuA, jsonField(queryId("HOSPITAL_A", "IDAAA").getBody(), "pseudonym"));
        assertEquals(pseuB, jsonField(queryId("HOSPITAL_B", "IDBBB").getBody(), "pseudonym"));
        assertNotEquals(pseuA, pseuB, "unresolved conflicts must never pre-merge indices");

        post("/api/patients/ingest", BRIDGE_CONFLICT, jsonWith("ingest-token-a"));
        HttpHeaders reviewer = new HttpHeaders();
        reviewer.setBearerAuth("reviewer-token");
        ResponseEntity<String> tickets = rest.exchange(url("/api/reviews/tickets"),
                HttpMethod.GET, new HttpEntity<>(reviewer), String.class);
        log.info("[TEST BASIS] pending tickets after two submits: {}", tickets.getBody());
        assertEquals(200, tickets.getStatusCode().value());
        int ticketCount = tickets.getBody().split("REV-").length - 1;
        assertEquals(1, ticketCount, "same pair+category pending must be deduplicated");
        assertTrue(tickets.getBody().contains("BIRTH_DATE"));
        assertTrue(tickets.getBody().contains("source-b"),
                "ticket records the first submitter; repeats are captured in audit + occurrenceCount");
        assertTrue(tickets.getBody().contains("\"occurrenceCount\":2"));
        assertFalse(tickets.getBody().contains("Zhao"));
        assertFalse(tickets.getBody().contains("Wang"));
        assertFalse(tickets.getBody().contains("IDAAA"));
    }

    @Test
    void reviewAuthorizationDistinguishesMissingInvalidAndForbidden() {
        ResponseEntity<String> noToken = rest.exchange(url("/api/reviews/tickets"),
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        assertEquals(401, noToken.getStatusCode().value());
        assertTrue(noToken.getBody().contains("MISSING_TOKEN"));

        HttpHeaders bad = new HttpHeaders();
        bad.setBearerAuth("nope");
        ResponseEntity<String> invalid = rest.exchange(url("/api/reviews/tickets"),
                HttpMethod.GET, new HttpEntity<>(bad), String.class);
        assertEquals(401, invalid.getStatusCode().value());
        assertTrue(invalid.getBody().contains("INVALID_TOKEN"));

        ResponseEntity<String> forbidden = rest.exchange(url("/api/reviews/tickets"),
                HttpMethod.GET, new HttpEntity<>(jsonWith("ingest-token-a")), String.class);
        assertEquals(403, forbidden.getStatusCode().value());
        assertTrue(forbidden.getBody().contains("FORBIDDEN_SCOPE"));

        ResponseEntity<String> preNoToken = post("/api/patients/precheck", PATIENT_A,
                new HttpHeaders());
        assertEquals(401, preNoToken.getStatusCode().value());
        assertTrue(preNoToken.getBody().contains("MISSING_TOKEN"));
        log.info("[TEST BASIS] review/precheck auth failures were distinguishable and audited");
    }

    @Test
    void approveMergesAtomicallyAndRepeatedDecisionsAreIdempotent() throws Exception {
        post("/api/patients/ingest", PATIENT_A, hl7With("ingest-token-a"));
        post("/api/patients/ingest", PATIENT_B, jsonWith("ingest-token-b"));
        String pseuA = jsonField(queryId("HOSPITAL_A", "IDAAA").getBody(), "pseudonym");
        post("/api/patients/ingest", BRIDGE_CONFLICT, jsonWith("ingest-token-b"));

        HttpHeaders reviewer = new HttpHeaders();
        reviewer.setBearerAuth("reviewer-token");
        ResponseEntity<String> tickets = rest.exchange(url("/api/reviews/tickets"),
                HttpMethod.GET, new HttpEntity<>(reviewer), String.class);
        String ticketId = jsonField(tickets.getBody(), "ticketId");
        log.info("[TEST INPUT] reviewer approves ticket {} concurrently x10", ticketId);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        java.util.Set<String> responses = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicInteger okCount = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ResponseEntity<String> r = rest.exchange(
                            url("/api/reviews/tickets/" + ticketId + "/decision"),
                            HttpMethod.POST,
                            new HttpEntity<>(Map.of("decision", "APPROVE"), reviewer), String.class);
                    if (r.getStatusCode().value() == 200) {
                        okCount.incrementAndGet();
                        responses.add(r.getBody());
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(threads, okCount.get());
        assertEquals(1, responses.size(), "repeated/concurrent decisions must return same result");
        log.info("[TEST BASIS] approval responses identical, merged view A={}",
                queryId("HOSPITAL_B", "IDBBB").getBody());
        assertEquals(pseuA, jsonField(queryId("HOSPITAL_B", "IDBBB").getBody(), "pseudonym"),
                "after approval both sides identifiers resolve to same stable pseudonym");
        assertEquals(pseuA, jsonField(queryId("HOSPITAL_A", "IDAAA").getBody(), "pseudonym"));
        ResponseEntity<String> repeat = rest.exchange(
                url("/api/reviews/tickets/" + ticketId + "/decision"),
                HttpMethod.POST, new HttpEntity<>(Map.of("decision", "APPROVE"), reviewer),
                String.class);
        assertTrue(repeat.getBody().contains("APPROVED"));
    }

    @Test
    void rejectionKeepsIndicesSeparateAndQueueFullIsDistinguishable() {
        post("/api/patients/ingest", PATIENT_A, hl7With("ingest-token-a"));
        post("/api/patients/ingest", PATIENT_B, jsonWith("ingest-token-b"));
        String pseuA = jsonField(queryId("HOSPITAL_A", "IDAAA").getBody(), "pseudonym");
        String pseuB = jsonField(queryId("HOSPITAL_B", "IDBBB").getBody(), "pseudonym");
        ResponseEntity<String> abConflict = post("/api/patients/ingest", BRIDGE_CONFLICT,
                jsonWith("ingest-token-b"));
        assertEquals(409, abConflict.getStatusCode().value());

        String cHl7 = """
                MSH|^~\\&|HIS_C|HOSPITAL_C|MPI|MPI|20261001120000||ADT^A01^ADT_A01|MSG-C|P|2.5.1
                PID|1||IDCCC^^^HOSPITAL_C^MR||Sun^Qi||19650101|O
                """;
        String dFhir = """
                {"resourceType":"Patient",
                 "identifier":[{"system":"HOSPITAL_D","value":"IDDDD"}],
                 "name":[{"family":"Zhou"}],"birthDate":"2001-02-02"}
                """;
        String bridgeCd = """
                {"resourceType":"Patient",
                 "identifier":[{"system":"HOSPITAL_C","value":"IDCCC"},
                               {"system":"HOSPITAL_D","value":"IDDDD"}],
                 "name":[{"family":"Sun"}],"birthDate":"1965-01-01"}
                """;
        post("/api/patients/ingest", cHl7, hl7With("ingest-token-a"));
        post("/api/patients/ingest", dFhir, jsonWith("ingest-token-b"));
        ResponseEntity<String> firstConflict = post("/api/patients/ingest", bridgeCd,
                jsonWith("ingest-token-b"));
        assertEquals(409, firstConflict.getStatusCode().value());
        assertTrue(firstConflict.getBody().contains("CONTRADICTORY_DATA"));

        String eHl7 = """
                MSH|^~\\&|HIS_E|EMR|MPI|MPI|20261001120000||ADT^A01^ADT_A01|MSG-E|P|2.5.1
                PID|1||IDEEE^^^EMR^MR||Sun^Qi||19650101|O
                """;
        String fFhir = """
                {"resourceType":"Patient",
                 "identifier":[{"system":"FMR","value":"IDFFF"}],
                 "name":[{"family":"Zhou"}],"birthDate":"2001-02-02"}
                """;
        String bridgeEf = """
                {"resourceType":"Patient",
                 "identifier":[{"system":"EMR","value":"IDEEE"},
                               {"system":"FMR","value":"IDFFF"}],
                 "name":[{"family":"Sun"}],"birthDate":"1965-01-01"}
                """;
        post("/api/patients/ingest", eHl7, hl7With("ingest-token-a"));
        post("/api/patients/ingest", fFhir, jsonWith("ingest-token-b"));
        ResponseEntity<String> third = post("/api/patients/ingest", bridgeEf,
                jsonWith("ingest-token-b"));
        log.info("[TEST INPUT] third distinct conflict with backlog limit=2");
        assertEquals(409, third.getStatusCode().value());
        assertTrue(third.getBody().contains("REVIEW_QUEUE_FULL"),
                "full backlog must be distinguishable from generic conflict");
        assertFalse(third.getBody().contains("Sun"), "rejection body must not leak plaintext");

        HttpHeaders reviewer = new HttpHeaders();
        reviewer.setBearerAuth("reviewer-token");
        String ticketId = jsonField(rest.exchange(url("/api/reviews/tickets"),
                HttpMethod.GET, new HttpEntity<>(reviewer), String.class).getBody(), "ticketId");
        ResponseEntity<String> rejected = rest.exchange(
                url("/api/reviews/tickets/" + ticketId + "/decision"),
                HttpMethod.POST, new HttpEntity<>(Map.of("decision", "REJECT"), reviewer),
                String.class);
        assertEquals(200, rejected.getStatusCode().value());
        assertTrue(rejected.getBody().contains("REJECTED"));
        assertEquals(pseuA, jsonField(queryId("HOSPITAL_A", "IDAAA").getBody(), "pseudonym"));
        assertEquals(pseuB, jsonField(queryId("HOSPITAL_B", "IDBBB").getBody(), "pseudonym"));
        assertNotEquals(pseuA, pseuB, "reject must leave identifier ownership untouched");
        log.info("[TEST BASIS] after rejection indices remain separate unchanged");
    }

    @Test
    void auditTrailForNewFlowsContainsNoPlaintext() {
        post("/api/patients/ingest", PATIENT_A, hl7With("ingest-token-a"));
        post("/api/patients/ingest", PATIENT_B, jsonWith("ingest-token-b"));
        post("/api/patients/precheck", BRIDGE_CONFLICT, jsonWith("ingest-token-b"));
        post("/api/patients/ingest", BRIDGE_CONFLICT, jsonWith("ingest-token-b"));
        rest.exchange(url("/api/reviews/tickets"), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);

        HttpHeaders admin = new HttpHeaders();
        admin.setBearerAuth("admin-token");
        ResponseEntity<String> audit = rest.exchange(url("/api/audit/events"),
                HttpMethod.GET, new HttpEntity<>(admin), String.class);
        String body = audit.getBody();
        log.info("[TEST BASIS] new-flow audit contains PRECHECK/REVIEW and no plaintext");
        assertTrue(body.contains("PRECHECK"));
        assertTrue(body.contains("DENIED:MISSING_TOKEN"));
        assertFalse(body.contains("Zhao"));
        assertFalse(body.contains("Wang"));
        assertFalse(body.contains("IDAAA"));
        assertFalse(body.contains("IDBBB"));
        assertFalse(body.contains("1988-03-20"));
        assertFalse(body.contains("1972-11-11"));
    }
}
