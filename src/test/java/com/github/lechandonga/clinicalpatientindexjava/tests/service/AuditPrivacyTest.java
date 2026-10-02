package com.github.lechandonga.clinicalpatientindexjava.tests.service;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditEvent;
import com.github.lechandonga.clinicalpatientindexjava.audit.AuditLog;
import com.github.lechandonga.clinicalpatientindexjava.domain.Gender;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.privacy.Pseudonymizer;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewQueue;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewStagingStore;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewTask;
import com.github.lechandonga.clinicalpatientindexjava.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新流程留痕隐私：预检（接受/拒绝/冲突/队列满）、待办登记/去重/队列满、
 * 裁决（批准/驳回/重复/相反）、越权失败等审计事件中，绝不出现姓名、
 * 原始标识符值或出生日期明文。
 */
class AuditPrivacyTest {

    private static final Logger log = LoggerFactory.getLogger(AuditPrivacyTest.class);

    private NormalizedPatient patient(String system, String value,
                                      String family, String birthDate) {
        NormalizedPatient p = new NormalizedPatient();
        p.getIdentifiers().add(new PatientIdentifier(system, value));
        p.setFamilyName(family);
        p.setGivenName("Secret");
        p.setBirthDate(birthDate);
        p.setGender(Gender.MALE);
        return p;
    }

    @Test
    void newFlowAuditEventsContainNoPlaintextSensitiveData() {
        Pseudonymizer pseudonymizer = new Pseudonymizer("privacy-test-secret");
        AuditLog auditLog = new AuditLog();
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewQueue queue = new ReviewQueue(1);
        ReviewService service = new ReviewService(index, queue, new ReviewStagingStore(),
                pseudonymizer, auditLog);

        index.upsert(patient("HOSPITAL_A", "SECRETID1", "Alpha", "1981-01-01"));
        index.upsert(patient("HOSPITAL_B", "SECRETID2", "Beta", "1972-02-02"));

        NormalizedPatient linkConflict = new NormalizedPatient();
        linkConflict.getIdentifiers().add(new PatientIdentifier("HOSPITAL_A", "SECRETID1"));
        linkConflict.getIdentifiers().add(new PatientIdentifier("HOSPITAL_B", "SECRETID2"));
        linkConflict.setFamilyName("Beta");
        linkConflict.setGivenName("Secret");
        linkConflict.setBirthDate("1972-02-02");
        linkConflict.setGender(Gender.MALE);

        // 预检：可接受的新患者 + 冲突（在队列占用前后再预检一次队列满）。
        service.preview(patient("HOSPITAL_C", "SECRETID3", "Gamma", "1963-03-03"),
                "HL7/2.5.1", "source-a");
        swallowConflict(() -> service.preview(linkConflict, "HL7/2.5.1", "source-a"));

        // 正式冲突登记待办 + 去重 + 队列满。
        swallowConflict(() -> index.upsert(linkConflict,
                c -> service.registerConflict(c, linkConflict, "source-a")));
        swallowConflict(() -> index.upsert(linkConflict,
                c -> service.registerConflict(c, linkConflict, "source-b")));
        index.upsert(patient("HOSPITAL_D", "SECRETID4", "Delta", "1964-04-04"));
        NormalizedPatient otherConflict = new NormalizedPatient();
        otherConflict.getIdentifiers().add(new PatientIdentifier("HOSPITAL_A", "SECRETID1"));
        otherConflict.getIdentifiers().add(new PatientIdentifier("HOSPITAL_D", "SECRETID4"));
        otherConflict.setFamilyName("Delta");
        otherConflict.setGivenName("Secret");
        otherConflict.setBirthDate("1964-04-04");
        otherConflict.setGender(Gender.MALE);
        swallowConflict(() -> index.upsert(otherConflict,
                c -> service.registerConflict(c, otherConflict, "source-a")));

        List<ReviewTask> open = service.listOpen("reviewer");
        String reviewId = open.get(0).getId();

        // 批准、重复批准。
        service.decide(reviewId, true, "reviewer-1");
        service.decide(reviewId, true, "reviewer-2");

        // 再制造一条并驳回，然后重复/相反裁决。
        index.upsert(patient("HOSPITAL_E", "SECRETID5", "Echo", "1955-05-05"));
        NormalizedPatient thirdConflict = new NormalizedPatient();
        thirdConflict.getIdentifiers().add(new PatientIdentifier("HOSPITAL_A", "SECRETID1"));
        thirdConflict.getIdentifiers().add(new PatientIdentifier("HOSPITAL_E", "SECRETID5"));
        thirdConflict.setFamilyName("Echo");
        thirdConflict.setGivenName("Secret");
        thirdConflict.setBirthDate("1955-05-05");
        thirdConflict.setGender(Gender.MALE);
        swallowConflict(() -> index.upsert(thirdConflict,
                c -> service.registerConflict(c, thirdConflict, "source-a")));
        String reviewId2 = service.listOpen("reviewer").get(0).getId();
        service.decide(reviewId2, false, "reviewer-1");
        service.decide(reviewId2, false, "reviewer-2");
        swallowConflict(() -> service.decide(reviewId2, true, "reviewer-3"));

        List<AuditEvent> events = auditLog.findAll();
        assertTrue(events.size() >= 8, "should exercise all new audit paths");
        for (AuditEvent event : events) {
            String joined = String.join("|",
                    nullSafe(event.actor()), nullSafe(event.action()),
                    nullSafe(event.targetMpiId()), nullSafe(event.targetPseudonym()),
                    nullSafe(event.identifierHash()), nullSafe(event.result()),
                    nullSafe(event.detail()));
            assertFalse(joined.contains("Alpha") || joined.contains("Beta")
                            || joined.contains("Gamma") || joined.contains("Delta")
                            || joined.contains("Echo") || joined.contains("Secret"),
                    "audit must not contain plaintext names: " + joined);
            assertFalse(joined.contains("SECRETID"),
                    "audit must not contain raw identifier values: " + joined);
            assertFalse(joined.contains("1981-01-01") || joined.contains("1972-02-02")
                            || joined.contains("1963-03-03") || joined.contains("1964-04-04")
                            || joined.contains("1955-05-05") || joined.contains("19810101"),
                    "audit must not contain plaintext birth dates: " + joined);
        }
        log.info("[TEST BASIS] scanned {} new-flow audit events; no plaintext names/ids/birthdates",
                events.size());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private void swallowConflict(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException expected) {
            log.info("[TEST BASIS] expected rejection: {}", expected.getMessage());
        }
    }
}
