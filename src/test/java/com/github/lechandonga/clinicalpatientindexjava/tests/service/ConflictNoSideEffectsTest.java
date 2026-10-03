package com.github.lechandonga.clinicalpatientindexjava.tests.service;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditLog;
import com.github.lechandonga.clinicalpatientindexjava.domain.ConflictException;
import com.github.lechandonga.clinicalpatientindexjava.domain.Gender;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientRecord;
import com.github.lechandonga.clinicalpatientindexjava.privacy.DeidentifiedPatientView;
import com.github.lechandonga.clinicalpatientindexjava.privacy.Pseudonymizer;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewQueue;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewStagingStore;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewTask;
import com.github.lechandonga.clinicalpatientindexjava.service.ReviewService;
import com.github.lechandonga.clinicalpatientindexjava.service.PatientQueryService;
import com.github.lechandonga.clinicalpatientindexjava.security.Caller;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定联调发现的缺陷：两个彼此一致的已有患者，被一条携带双方标识、
 * 但来件人口学（出生日期或姓氏）冲突的消息串起来时，拒绝必须零副作用，
 * 待办必须覆盖双方，裁决行为必须原子且幂等。
 */
class ConflictNoSideEffectsTest {

    private static final Logger log = LoggerFactory.getLogger(ConflictNoSideEffectsTest.class);

    private final Pseudonymizer pseudonymizer =
            new Pseudonymizer("unit-test-secret");

    private NormalizedPatient singleId(String system, String value,
                                       String family, String birthDate) {
        NormalizedPatient p = new NormalizedPatient();
        p.getIdentifiers().add(new PatientIdentifier(system, value));
        p.setFamilyName(family);
        p.setGivenName("Tester");
        p.setBirthDate(birthDate);
        p.setGender(Gender.FEMALE);
        return p;
    }

    private NormalizedPatient linking(String valueA, String valueB,
                                      String family, String birthDate) {
        NormalizedPatient p = new NormalizedPatient();
        p.getIdentifiers().add(new PatientIdentifier("HOSPITAL_A", valueA));
        p.getIdentifiers().add(new PatientIdentifier("HOSPITAL_B", valueB));
        p.setFamilyName(family);
        p.setGivenName("Tester");
        p.setBirthDate(birthDate);
        p.setGender(Gender.FEMALE);
        return p;
    }

    private NormalizedPatient conflictLink(String systemA, String valueA,
                                           String systemB, String valueB,
                                           String family, String birthDate) {
        NormalizedPatient p = new NormalizedPatient();
        p.getIdentifiers().add(new PatientIdentifier(systemA, valueA));
        p.getIdentifiers().add(new PatientIdentifier(systemB, valueB));
        p.setFamilyName(family);
        p.setGivenName("Tester");
        p.setBirthDate(birthDate);
        p.setGender(Gender.FEMALE);
        return p;
    }

    private IngestionException reject(MasterPatientIndex index, ReviewService service,
                                      NormalizedPatient message, String actor) {
        return assertThrows(IngestionException.class,
                () -> index.upsert(message, c -> service.registerConflict(c, message, actor)));
    }

    private void assertRejectionUntouched(MasterPatientIndex index,
                                          String mpiA, String mpiB,
                                          String family, String birthDate) {
        assertRejectionUntouched(index, mpiA, mpiB, family, birthDate,
                2, 2);
    }

    private void assertRejectionUntouched(MasterPatientIndex index,
                                          String mpiA, String mpiB,
                                          String family, String birthDate,
                                          int expectedRecordCount,
                                          int expectedIdentifierCount) {
        assertEquals(expectedRecordCount, index.recordCount(),
                "rejected conflict must not merge clusters");
        assertEquals(expectedIdentifierCount, index.identifierCount(),
                "rejected conflict must not move identifiers");
        MasterPatientRecord byA = index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_A", "7001"));
        MasterPatientRecord byB = index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_B", "7002"));
        assertNotNull(byA);
        assertNotNull(byB);
        assertEquals(mpiA, byA.getMpiId(), "identifier A must stay on its original MPI");
        assertEquals(mpiB, byB.getMpiId(), "identifier B must stay on its original MPI");
        assertEquals(1, byA.getIdentifiers().size());
        assertEquals(1, byB.getIdentifiers().size());
        assertEquals(family, byA.getFamilyName());
        assertEquals(family, byB.getFamilyName());
        assertEquals(birthDate, byA.getBirthDate());
        assertEquals(birthDate, byB.getBirthDate());
    }

    private ReviewService newService(MasterPatientIndex index, int queueLimit) {
        return new ReviewService(index, new ReviewQueue(queueLimit),
                new ReviewStagingStore(), pseudonymizer, new AuditLog());
    }

    /**
     * 两个患者分两次登记，人口学完全一致（同名同生日）；之后用一条双标识、
     * 出生日期冲突的消息链接。拒绝前后索引必须逐字节不变。
     */
    @Test
    void incomingBirthDateConflictBetweenConsistentPatientsHasNoSideEffects() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = newService(index, 100);
        String mpiA = index.upsert(singleId("HOSPITAL_A", "7001", "Zhang", "1990-05-05")).mpiId();
        String mpiB = index.upsert(singleId("HOSPITAL_B", "7002", "Zhang", "1990-05-05")).mpiId();
        assertNotEquals(mpiA, mpiB);

        NormalizedPatient bad = linking("7001", "7002", "Zhang", "1988-08-08");
        log.info("[TEST INPUT] two consistent patients linked by message with conflicting "
                + "birthDate; summary={}", bad.summary());
        IngestionException ex = reject(index, service, bad, "source-a");
        log.info("[TEST BASIS] rejection reason={} message={}", ex.getReason(), ex.getMessage());
        assertEquals(IngestionException.RejectionReason.CONTRADICTORY_DATA, ex.getReason());

        assertRejectionUntouched(index, mpiA, mpiB, "Zhang", "1990-05-05");

        PatientQueryService queries = new PatientQueryService(index, pseudonymizer, new AuditLog());
        Caller caller = new Caller("reviewer", Set.of("query"));
        DeidentifiedPatientView viewA =
                queries.queryByIdentifier(caller, "HOSPITAL_A", "7001");
        DeidentifiedPatientView viewB =
                queries.queryByIdentifier(caller, "HOSPITAL_B", "7002");
        assertNotEquals(viewA.pseudonym(), viewB.pseudonym(),
                "before decision, each identifier must still resolve to its own MPI");
        assertEquals(1, viewA.linkedIdentifierCount());
        assertEquals(1, viewB.linkedIdentifierCount());
        log.info("[TEST BASIS] post-rejection views stay separate: {} vs {}",
                viewA.pseudonym(), viewB.pseudonym());

        List<ReviewTask> open = service.listOpen("reviewer");
        assertEquals(1, open.size());
        ReviewTask task = open.get(0);
        log.info("[TEST BASIS] task={} mpiIds={} pseudonyms={} type={} by={} at={} count={}",
                task.getId(), task.getMpiIds(), task.getPseudonyms(), task.getConflictType(),
                task.getSubmittedBy(), task.getSubmittedAt(), task.getSubmissionCount());
        assertEquals(List.of(mpiA, mpiB), task.getMpiIds());
        assertEquals(2, task.getPseudonyms().size());
        assertEquals(List.of(pseudonymizer.pseudonym(mpiA),
                pseudonymizer.pseudonym(mpiB)), task.getPseudonyms());
        assertEquals(ConflictException.ConflictType.BIRTH_DATE, task.getConflictType());
        assertEquals("source-a", task.getSubmittedBy());
        assertNotNull(task.getSubmittedAt());
        assertEquals(2, task.getIncomingIdentifierHashes().size());
        assertTrue(task.getFieldMergePlan().size() >= 3);

        reject(index, service, bad, "source-b");
        reject(index, service, bad, "source-a");
        List<ReviewTask> stillOne = service.listOpen("reviewer");
        assertEquals(1, stillOne.size());
        assertEquals(3, stillOne.get(0).getSubmissionCount());
        assertRejectionUntouched(index, mpiA, mpiB, "Zhang", "1990-05-05");
        log.info("[TEST BASIS] repeated rejection deduped; submissionCount={} records={} ids={}",
                stillOne.get(0).getSubmissionCount(), index.recordCount(),
                index.identifierCount());

        ReviewService.DecisionResult rejected = service.decide(task.getId(), false, "reviewer-1");
        assertEquals("REJECTED", rejected.decision());
        assertRejectionUntouched(index, mpiA, mpiB, "Zhang", "1990-05-05");

        ReviewService.DecisionResult repeat = service.decide(task.getId(), false, "reviewer-2");
        assertEquals("REJECTED", repeat.decision());
        assertEquals(false, repeat.applied());
        IngestionException opposite = assertThrows(IngestionException.class,
                () -> service.decide(task.getId(), true, "reviewer-2"));
        assertEquals(IngestionException.RejectionReason.REVIEW_ALREADY_DECIDED,
                opposite.getReason());
        assertRejectionUntouched(index, mpiA, mpiB, "Zhang", "1990-05-05");
    }

    /**
     * 姓氏不一致但出生日期相同：同样拒绝零副作用，冲突类别为 FAMILY_NAME。
     */
    @Test
    void incomingFamilyNameConflictWithSameBirthDateHasNoSideEffects() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = newService(index, 100);
        String mpiA = index.upsert(singleId("HOSPITAL_A", "7001", "Zhang", "1990-05-05")).mpiId();
        String mpiB = index.upsert(singleId("HOSPITAL_B", "7002", "Zhang", "1990-05-05")).mpiId();

        NormalizedPatient bad = linking("7001", "7002", "Wang", "1990-05-05");
        log.info("[TEST INPUT] consistent patients linked with conflicting family name; {}",
                bad.summary());
        IngestionException ex = reject(index, service, bad, "source-a");
        assertEquals(IngestionException.RejectionReason.CONTRADICTORY_DATA, ex.getReason());
        assertRejectionUntouched(index, mpiA, mpiB, "Zhang", "1990-05-05");

        ReviewTask task = service.listOpen("reviewer").get(0);
        log.info("[TEST BASIS] conflict type={} mpiIds={}", task.getConflictType(),
                task.getMpiIds());
        assertEquals(ConflictException.ConflictType.FAMILY_NAME, task.getConflictType());
        assertEquals(List.of(mpiA, mpiB), task.getMpiIds());

        reject(index, service, bad, "source-a");
        assertEquals(1, service.listOpen("reviewer").size());
        assertRejectionUntouched(index, mpiA, mpiB, "Zhang", "1990-05-05");

        ReviewService.DecisionResult rejected = service.decide(task.getId(), false, "reviewer");
        assertEquals("REJECTED", rejected.decision());
        assertRejectionUntouched(index, mpiA, mpiB, "Zhang", "1990-05-05");
    }

    /**
     * 待办打满：返回可区分的 REVIEW_QUEUE_FULL，且不产生任何数据改动。
     */
    @Test
    void queueFullRejectsWithDistinctReasonAndNoDataChange() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = newService(index, 1);
        index.upsert(singleId("HOSPITAL_C", "8001", "Li", "1970-01-01"));
        index.upsert(singleId("HOSPITAL_D", "8002", "Chen", "1960-06-06"));
        reject(index, service,
                conflictLink("HOSPITAL_C", "8001", "HOSPITAL_D", "8002",
                        "Chen", "1960-06-06"), "source-a");
        assertEquals(1, service.listOpen("reviewer").size());

        String mpiA = index.upsert(singleId("HOSPITAL_A", "7001", "Zhang", "1990-05-05")).mpiId();
        String mpiB = index.upsert(singleId("HOSPITAL_B", "7002", "Zhang", "1990-05-05")).mpiId();
        NormalizedPatient bad = linking("7001", "7002", "Zhang", "1988-08-08");
        log.info("[TEST INPUT] conflict submitted while review queue is full");
        IngestionException ex = reject(index, service, bad, "source-b");
        log.info("[TEST BASIS] rejection reason={}", ex.getReason());
        assertEquals(IngestionException.RejectionReason.REVIEW_QUEUE_FULL, ex.getReason());
        assertEquals(1, service.listOpen("reviewer").size(), "no extra task is created");
        assertRejectionUntouched(index, mpiA, mpiB, "Zhang", "1990-05-05", 4, 4);

        assertEquals(IngestionException.RejectionReason.REVIEW_QUEUE_FULL,
                reject(index, service, bad, "source-b").getReason());
        assertEquals(1, service.listOpen("reviewer").size());
        assertRejectionUntouched(index, mpiA, mpiB, "Zhang", "1990-05-05", 4, 4);
    }

    /**
     * 批准一次性原子完成：两边标识落同一主索引，替代标识稳定，
     * 字段取舍为“字典序较小 MPI 的非空值优先”，裁决幂等。
     */
    @Test
    void approvalMergesBothSidesAtomicallyWithDeterministicFields() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = newService(index, 100);
        String mpiA = index.upsert(singleId("HOSPITAL_A", "7001", "Zhang", "1990-05-05")).mpiId();
        String mpiB = index.upsert(singleId("HOSPITAL_B", "7002", "Zhang", "1990-05-05")).mpiId();
        NormalizedPatient bad = linking("7001", "7002", "Zhang", "1988-08-08");
        reject(index, service, bad, "source-a");
        ReviewTask task = service.listOpen("reviewer").get(0);

        ReviewService.DecisionResult approved = service.decide(task.getId(), true, "reviewer");
        log.info("[TEST BASIS] approval decision={} applied={} basis={}",
                approved.decision(), approved.applied(), approved.basis());
        assertEquals("APPROVED", approved.decision());

        MasterPatientRecord byA = index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_A", "7001"));
        MasterPatientRecord byB = index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_B", "7002"));
        assertNotNull(byA);
        assertEquals(byA.getMpiId(), byB.getMpiId());
        assertEquals(1, index.recordCount());
        assertEquals(2, index.identifierCount());
        String survivor = List.of(mpiA, mpiB).stream().min(String::compareTo).orElseThrow();
        assertEquals(survivor, byA.getMpiId());
        assertEquals(pseudonymizer.pseudonym(survivor),
                pseudonymizer.pseudonym(byA.getMpiId()), "pseudonym stays stable");
        assertEquals("1990-05-05", byA.getBirthDate(),
                "survivor/existing value wins over the conflicting incoming value");
        assertEquals("Zhang", byA.getFamilyName());

        ReviewService.DecisionResult repeat = service.decide(task.getId(), true, "reviewer");
        assertEquals(false, repeat.applied());
        assertEquals("APPROVED", repeat.decision());
        assertEquals(1, index.recordCount());

        PatientQueryService queries = new PatientQueryService(index, pseudonymizer, new AuditLog());
        Caller caller = new Caller("reviewer", Set.of("query"));
        DeidentifiedPatientView viewA =
                queries.queryByIdentifier(caller, "HOSPITAL_A", "7001");
        DeidentifiedPatientView viewB =
                queries.queryByIdentifier(caller, "HOSPITAL_B", "7002");
        assertEquals(viewA, viewB,
                "after approval, both identifiers must expose the same deidentified view");
        log.info("[TEST BASIS] post-approval views identical: pseudonym={} identifiers={} year={}",
                viewA.pseudonym(), viewA.linkedIdentifierCount(), viewA.birthYear());
    }

    /**
     * 并发穿插：重复提交与裁决同时进行，不出现重复主索引、半合并或
     * “拒绝却改了数据”；结束后任意标识查询视图一致。
     */
    @Test
    void concurrentRepeatedSubmissionsAndDecisionStayConsistent() throws Exception {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = newService(index, 100);
        String mpiA = index.upsert(singleId("HOSPITAL_A", "7001", "Zhang", "1990-05-05")).mpiId();
        String mpiB = index.upsert(singleId("HOSPITAL_B", "7002", "Zhang", "1990-05-05")).mpiId();
        NormalizedPatient bad = linking("7001", "7002", "Zhang", "1988-08-08");
        reject(index, service, bad, "source-a");
        String reviewId = service.listOpen("reviewer").get(0).getId();

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> decisions = ConcurrentHashMap.newKeySet();
        Set<String> rejectionReasons = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            final int seed = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (seed % 4 == 0) {
                        decisions.add(service.decide(reviewId, true, "reviewer").decision());
                    } else {
                        try {
                            index.upsert(bad, c -> service.registerConflict(c, bad, "source-x"));
                        } catch (IngestionException e) {
                            rejectionReasons.add(e.getReason().name());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        log.info("[TEST INPUT] {} threads interleaving APPROVE and repeated conflicting ingest",
                threads);
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[TEST BASIS] decisions={} pre-merge rejectionReasons={} records={} ids={}",
                decisions, rejectionReasons, index.recordCount(), index.identifierCount());
        assertEquals(Set.of("APPROVED"), decisions);
        MasterPatientRecord byA = index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_A", "7001"));
        MasterPatientRecord byB = index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_B", "7002"));
        assertNotNull(byA);
        assertEquals(byA.getMpiId(), byB.getMpiId(), "final view from either identifier is identical");
        assertEquals(1, index.recordCount());
        assertEquals(2, index.identifierCount());
        assertEquals("1990-05-05", byA.getBirthDate());
        assertEquals("Zhang", byA.getFamilyName());
        assertEquals(mpiA, byA.getMpiId(), "lower MPI id deterministically survives");
    }
}
