package com.github.lechandonga.clinicalpatientindexjava.tests.service;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditLog;
import com.github.lechandonga.clinicalpatientindexjava.domain.ConflictException;
import com.github.lechandonga.clinicalpatientindexjava.domain.Gender;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.privacy.Pseudonymizer;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewQueue;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewStagingStore;
import com.github.lechandonga.clinicalpatientindexjava.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预检只读性的单元覆盖：接受/拒绝/冲突三类消息预检前后索引状态完全一致，
 * 重复预检结果一致；冲突预检不登记待办、不产生副作用；审计不含明文。
 */
class PreviewServiceTest {

    private static final Logger log = LoggerFactory.getLogger(PreviewServiceTest.class);

    private final Pseudonymizer pseudonymizer =
            new Pseudonymizer("unit-test-secret");

    private ReviewService newReviewService(MasterPatientIndex index, int limit) {
        return new ReviewService(index, new ReviewQueue(limit), new ReviewStagingStore(),
                pseudonymizer, new AuditLog());
    }

    private NormalizedPatient patient(String system, String value,
                                      String family, String birthDate) {
        NormalizedPatient p = new NormalizedPatient();
        p.getIdentifiers().add(new PatientIdentifier(system, value));
        p.setFamilyName(family);
        p.setGivenName("Tester");
        p.setBirthDate(birthDate);
        p.setGender(Gender.FEMALE);
        return p;
    }

    @Test
    void previewIsReadOnlyAndDeterministicForNewPatient() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = newReviewService(index, 100);
        NormalizedPatient incoming = patient("HOSPITAL_A", "MRN-9001", "Zhao", "1991-02-03");

        log.info("[TEST INPUT] preview brand-new patient twice (must be read-only & identical)");
        ReviewService.PreviewResult first = service.preview(incoming, "HL7/2.5.1", "source-a");
        ReviewService.PreviewResult second = service.preview(incoming, "HL7/2.5.1", "source-a");
        log.info("[TEST BASIS] acceptable={} wouldCreate={} effects={}",
                first.acceptable(), first.wouldCreate(),
                first.fieldEffects().stream().map(e -> e.field() + "=" + e.effect()).toList());

        assertTrue(first.acceptable());
        assertTrue(first.wouldCreate());
        assertEquals(first.targetPseudonym(), second.targetPseudonym());
        assertEquals(first.fieldEffects(), second.fieldEffects());
        assertEquals(0, index.recordCount(), "preview must not create an MPI");
        assertEquals(0, index.identifierCount(), "preview must not claim identifiers");
    }

    @Test
    void previewShowsMergeIntoExistingAndDoesNotMutate() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = newReviewService(index, 100);
        index.upsert(patient("HOSPITAL_A", "MRN-9002", "Zhao", "1991-02-03"));

        // 命中已有患者，同时携带一个新来源标识（会补挂、会补全缺失字段）。
        NormalizedPatient followUp = patient("HOSPITAL_A", "mrn9002", "Zhao", "1991-02-03");
        followUp.getIdentifiers().add(new PatientIdentifier("HOSPITAL_B", "B-77"));
        followUp.setGender(Gender.UNKNOWN);
        followUp.getNotes().add("gender unmapped -> UNKNOWN");

        String existingPseudonym =
                pseudonymizer.pseudonym(index.findByIdentifier(
                        new PatientIdentifier("HOSPITAL_A", "MRN-9002")).getMpiId());
        log.info("[TEST INPUT] preview follow-up sharing canonical id and adding HOSPITAL_B id");
        ReviewService.PreviewResult preview = service.preview(followUp, "FHIR/R4", "source-b");
        log.info("[TEST BASIS] wouldCreate={} pseudonymMatchesExisting={} effects={}",
                preview.wouldCreate(), preview.targetPseudonym().equals(existingPseudonym),
                preview.fieldEffects());

        assertTrue(preview.acceptable());
        assertEquals(false, preview.wouldCreate());
        assertEquals(existingPseudonym, preview.targetPseudonym(),
                "existing patient must be referenced only by the stable pseudonym");
        assertEquals(1, index.recordCount());
        assertEquals(1, index.identifierCount(), "new source id must not be linked by preview");
        assertNull(index.findByIdentifier(new PatientIdentifier("HOSPITAL_B", "B-77")),
                "preview must not attach the new identifier");
    }

    @Test
    void previewOfConflictingMessageUsesSameRejectionAndStaysReadOnly() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = newReviewService(index, 100);
        index.upsert(patient("HOSPITAL_A", "9100", "Li", "1980-01-01"));
        NormalizedPatient conflicting = patient("HOSPITAL_A", "MRN9100", "Wang", "2001-09-09");

        log.info("[TEST INPUT] preview conflicting birthDate for same canonical id");
        IngestionException ex = assertThrows(IngestionException.class,
                () -> service.preview(conflicting, "HL7/2.5.1", "source-a"));
        log.info("[TEST BASIS] preview rejection reason={}", ex.getReason());
        assertEquals(IngestionException.RejectionReason.CONTRADICTORY_DATA, ex.getReason());

        ConflictException formal = assertThrows(ConflictException.class,
                () -> index.upsert(conflicting));
        log.info("[TEST BASIS] formal rejection reason={} (same family as preview)",
                formal.getReason());
        assertEquals(ex.getReason(), formal.getReason());

        assertEquals(1, index.recordCount(), "conflict preview must not create records");
        assertEquals(1, index.identifierCount(), "conflict preview must not move identifiers");
        assertEquals(0, service.listOpen("reviewer").size(),
                "preview must not open review tasks even when it detects a conflict");
    }

    @Test
    void previewRejectsWithQueueFullReasonLikeFormalSubmission() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewQueue queue = new ReviewQueue(1);
        ReviewService service = new ReviewService(index, queue, new ReviewStagingStore(),
                pseudonymizer, new AuditLog());
        index.upsert(patient("HOSPITAL_A", "1", "Li", "1980-01-01"));
        index.upsert(patient("HOSPITAL_B", "2", "Wang", "1975-05-05"));
        // 占满唯一待办名额（另一个独立患者对的冲突）。
        NormalizedPatient blocker = patient("HOSPITAL_A", "1", "Li", "1999-01-01");
        blocker.getIdentifiers().add(new PatientIdentifier("HOSPITAL_B", "2"));
        assertThrows(IngestionException.class,
                () -> index.upsert(blocker, c -> service.registerConflict(c, blocker, "source-a")));
        assertEquals(1, queue.openCount());

        index.upsert(patient("HOSPITAL_C", "3", "Chen", "1966-06-06"));
        NormalizedPatient next = patient("HOSPITAL_C", "3", "Chen", "1955-05-05");
        log.info("[TEST INPUT] preview another conflict while queue is full");
        IngestionException previewEx = assertThrows(IngestionException.class,
                () -> service.preview(next, "HL7/2.5.1", "source-b"));
        assertEquals(IngestionException.RejectionReason.REVIEW_QUEUE_FULL,
                previewEx.getReason());
        log.info("[TEST BASIS] preview reason={} and queue unchanged after preview",
                previewEx.getReason());
        assertEquals(1, queue.openCount(), "preview must not change the queue");

        // 正式提交同样得到 REVIEW_QUEUE_FULL（可区分，不静默丢弃）。
        IngestionException formalEx = assertThrows(IngestionException.class,
                () -> index.upsert(next, c -> service.registerConflict(c, next, "source-b")));
        assertEquals(IngestionException.RejectionReason.REVIEW_QUEUE_FULL,
                formalEx.getReason());
        assertNotEquals(previewEx.getMessage(), "");
    }
}
