package com.github.lechandonga.clinicalpatientindexjava.tests.service;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditLog;
import com.github.lechandonga.clinicalpatientindexjava.domain.ConflictException;
import com.github.lechandonga.clinicalpatientindexjava.domain.Gender;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientRecord;
import com.github.lechandonga.clinicalpatientindexjava.privacy.Pseudonymizer;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewQueue;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewStagingStore;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewTask;
import com.github.lechandonga.clinicalpatientindexjava.service.ReviewService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固化“被拒绝的冲突提交零副作用”语义：
 * 两个已存在患者彼此一致、只有来件与已有记录冲突（出生日期 / 姓氏两类），
 * 拒绝前后主索引数量、标识归属、人口学与按标识查询的结果必须完全一致；
 * 待办如实覆盖双方；反复提交去重；队列满有独立原因且不改数据；
 * 批准原子合并且取舍可解释，驳回回到原状；并发交叉下结果一致、裁决幂等。
 */
class ConflictRejectionIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(ConflictRejectionIsolationTest.class);

    private final Pseudonymizer pseudonymizer = new Pseudonymizer("unit-test-secret");

    private NormalizedPatient singleId(String system, String value,
                                       String family, String given, String birthDate) {
        NormalizedPatient p = new NormalizedPatient();
        p.getIdentifiers().add(new PatientIdentifier(system, value));
        p.setFamilyName(family);
        p.setGivenName(given);
        p.setBirthDate(birthDate);
        p.setGender(Gender.FEMALE);
        return p;
    }

    private NormalizedPatient linkingMessage(String family, String given, String birthDate,
                                             String aValue, String bValue) {
        NormalizedPatient p = new NormalizedPatient();
        p.getIdentifiers().add(new PatientIdentifier("HOSPITAL_A", aValue));
        p.getIdentifiers().add(new PatientIdentifier("HOSPITAL_B", bValue));
        p.setFamilyName(family);
        p.setGivenName(given);
        p.setBirthDate(birthDate);
        p.setGender(Gender.FEMALE);
        return p;
    }

    private IngestionException submitConflict(MasterPatientIndex index, ReviewService service,
                                              NormalizedPatient message, String actor) {
        return assertThrows(IngestionException.class,
                () -> index.upsert(message, c -> service.registerConflict(c, message, actor)));
    }

    /** 拒绝后两个患者必须仍各自独立：主索引数量、标识归属、人口学全部原样。 */
    private void assertPatientsUntouched(MasterPatientIndex index,
                                         String idA, String idB,
                                         String mpiA, String mpiB,
                                         String family, String birthDate) {
        assertEquals(2, index.recordCount(), "rejected submission must not create/merge MPI records");
        assertEquals(2, index.identifierCount(), "rejected submission must not relink identifiers");
        MasterPatientRecord byA = index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_A", idA));
        MasterPatientRecord byB = index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_B", idB));
        assertNotNull(byA);
        assertNotNull(byB);
        assertEquals(mpiA, byA.getMpiId(), "first identifier must still point at its original MPI");
        assertEquals(mpiB, byB.getMpiId(), "second identifier must still point at its original MPI");
        assertFalse(byA.getMpiId().equals(byB.getMpiId()), "no merge may happen before adjudication");
        assertEquals(1, byA.getIdentifiers().size());
        assertEquals(1, byB.getIdentifiers().size());
        assertEquals(family, byA.getFamilyName());
        assertEquals(family, byB.getFamilyName());
        assertEquals(birthDate, byA.getBirthDate());
        assertEquals(birthDate, byB.getBirthDate());
    }

    /** 待办必须如实覆盖双方：两个稳定替代标识、冲突类别、提交人、提交时间与取舍规则。 */
    private ReviewTask assertTaskCoversBothPatients(ReviewService service,
                                                    String mpiA, String mpiB,
                                                    ConflictException.ConflictType type) {
        List<ReviewTask> open = service.listOpen("reviewer");
        assertEquals(1, open.size());
        ReviewTask task = open.get(0);
        assertEquals(List.of(mpiA, mpiB), task.getMpiIds(),
                "review task must list both linked patients, not just one");
        assertEquals(2, task.getPseudonyms().size(),
                "review task must expose a stable pseudonym per patient");
        assertEquals(Set.of(pseudonymizer.pseudonym(mpiA), pseudonymizer.pseudonym(mpiB)),
                Set.copyOf(task.getPseudonyms()));
        assertEquals(type, task.getConflictType());
        assertEquals("source-a", task.getSubmittedBy());
        assertNotNull(task.getSubmittedAt());
        assertFalse(task.getPseudonyms().contains(mpiA), "task must not carry plaintext MPI ids");
        assertFalse(task.getPseudonyms().contains(mpiB));
        assertTrue(task.getFieldMergePlan().size() >= 3,
                "task must document the deterministic field resolution rules");
        log.info("[TEST BASIS] task covers both patients: mpiIds={} pseudonyms={} type={} by={}",
                task.getMpiIds(), task.getPseudonyms(), task.getConflictType(),
                task.getSubmittedBy());
        return task;
    }

    /**
     * 复现缺陷的核心场景：两个已登记患者彼此不冲突，
     * 串接消息的出生日期与已有记录不一致（姓氏一致）。
     */
    @Test
    void rejectedBirthDateConflictLeavesTwoPatientsFullyUntouched() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = new ReviewService(index, new ReviewQueue(100),
                new ReviewStagingStore(), pseudonymizer, new AuditLog());
        String mpiA = index.upsert(singleId("HOSPITAL_A", "7001", "Zhang", "Wei",
                "1990-05-05")).mpiId();
        String mpiB = index.upsert(singleId("HOSPITAL_B", "8001", "Zhang", "Wei",
                "1990-05-05")).mpiId();
        log.info("[TEST INPUT] two separate registrations: idA->{} idB->{} same demographics",
                mpiA, mpiB);

        NormalizedPatient conflict = linkingMessage("Zhang", "Wei", "2001-09-09",
                "7001", "8001");
        IngestionException ex = submitConflict(index, service, conflict, "source-a");
        assertEquals(IngestionException.RejectionReason.CONTRADICTORY_DATA, ex.getReason());
        log.info("[TEST BASIS] birthDate conflict rejected reason={}; asserting zero side effects",
                ex.getReason());

        assertPatientsUntouched(index, "7001", "8001", mpiA, mpiB, "Zhang", "1990-05-05");
        assertTaskCoversBothPatients(service, mpiA, mpiB, ConflictException.ConflictType.BIRTH_DATE);

        // 同一条消息反复提交：同样的拒绝结果、不堆待办、不产生任何数据改动。
        for (int i = 0; i < 3; i++) {
            IngestionException repeated = submitConflict(index, service, conflict,
                    i % 2 == 0 ? "source-b" : "source-a");
            assertEquals(IngestionException.RejectionReason.CONTRADICTORY_DATA,
                    repeated.getReason());
            assertPatientsUntouched(index, "7001", "8001", mpiA, mpiB, "Zhang", "1990-05-05");
        }
        List<ReviewTask> open = service.listOpen("reviewer");
        assertEquals(1, open.size());
        assertEquals(4, open.get(0).getSubmissionCount(),
                "resubmissions must be deduplicated onto the same open task");
        log.info("[TEST BASIS] repeated rejects stay identical; openTasks={} submissionCount={}",
                open.size(), open.get(0).getSubmissionCount());

        // 驳回后两边归属与人口学原样不动。
        ReviewService.DecisionResult rejected = service.decide(open.get(0).getId(), false, "reviewer");
        assertEquals("REJECTED", rejected.decision());
        assertTrue(rejected.applied());
        assertPatientsUntouched(index, "7001", "8001", mpiA, mpiB, "Zhang", "1990-05-05");
        // 驳回决定同样幂等。
        ReviewService.DecisionResult repeatedDecision =
                service.decide(open.get(0).getId(), false, "reviewer-2");
        assertFalse(repeatedDecision.applied());
        assertEquals("REJECTED", repeatedDecision.decision());
        assertPatientsUntouched(index, "7001", "8001", mpiA, mpiB, "Zhang", "1990-05-05");
        log.info("[TEST BASIS] after rejection both identifiers still resolve to original MPIs");
    }

    /**
     * 同类缺陷的姓氏版本：出生日期一致、姓氏与已有记录冲突。
     */
    @Test
    void rejectedFamilyNameConflictLeavesTwoPatientsFullyUntouched() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = new ReviewService(index, new ReviewQueue(100),
                new ReviewStagingStore(), pseudonymizer, new AuditLog());
        String mpiA = index.upsert(singleId("HOSPITAL_A", "7101", "Zhang", "Wei",
                "1990-05-05")).mpiId();
        String mpiB = index.upsert(singleId("HOSPITAL_B", "8101", "Zhang", "Wei",
                "1990-05-05")).mpiId();
        log.info("[TEST INPUT] two patients with same birth date; linking message changes family name");

        NormalizedPatient conflict = linkingMessage("Other", "Wei", "1990-05-05",
                "7101", "8101");
        IngestionException ex = submitConflict(index, service, conflict, "source-a");
        assertEquals(IngestionException.RejectionReason.CONTRADICTORY_DATA, ex.getReason());
        log.info("[TEST BASIS] familyName conflict rejected reason={}", ex.getReason());

        assertPatientsUntouched(index, "7101", "8101", mpiA, mpiB, "Zhang", "1990-05-05");
        assertTaskCoversBothPatients(service, mpiA, mpiB, ConflictException.ConflictType.FAMILY_NAME);

        submitConflict(index, service, conflict, "source-b");
        List<ReviewTask> open = service.listOpen("reviewer");
        assertEquals(1, open.size());
        assertEquals(2, open.get(0).getSubmissionCount());
        assertPatientsUntouched(index, "7101", "8101", mpiA, mpiB, "Zhang", "1990-05-05");
    }

    /**
     * 待办打满：原因码必须可区分，且同样零数据改动、不堆待办。
     */
    @Test
    void fullQueueReturnsDistinctReasonAndLeavesIndexUntouched() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = new ReviewService(index, new ReviewQueue(1),
                new ReviewStagingStore(), pseudonymizer, new AuditLog());
        String mpiA = index.upsert(singleId("HOSPITAL_A", "7201", "Zhang", "Wei",
                "1990-05-05")).mpiId();
        String mpiB = index.upsert(singleId("HOSPITAL_B", "8201", "Zhang", "Wei",
                "1990-05-05")).mpiId();
        String mpiC = index.upsert(singleId("HOSPITAL_C", "9201", "Zhang", "Wei",
                "1990-05-05")).mpiId();

        submitConflict(index, service,
                linkingMessage("Zhang", "Wei", "2001-01-01", "7201", "8201"), "source-a");
        assertEquals(1, service.listOpen("reviewer").size());

        NormalizedPatient secondConflict = linkingMessage("Zhang", "Wei", "2002-02-02",
                "7201", "9201");
        log.info("[TEST INPUT] queue limit=1; second conflicting pair submitted");
        IngestionException ex = submitConflict(index, service, secondConflict, "source-b");
        log.info("[TEST BASIS] rejection reason={}", ex.getReason());
        assertEquals(IngestionException.RejectionReason.REVIEW_QUEUE_FULL, ex.getReason());

        assertEquals(1, service.listOpen("reviewer").size(), "full queue must not stack tasks");
        assertEquals(3, index.recordCount());
        assertEquals(3, index.identifierCount());
        assertEquals(mpiA, index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_A", "7201")).getMpiId());
        assertEquals(mpiB, index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_B", "8201")).getMpiId());
        assertEquals(mpiC, index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_C", "9201")).getMpiId());
    }

    /**
     * 批准一次性原子完成：两边标识落到同一主索引，替代标识稳定，
     * 人口学取舍遵循待办中记录的确定性规则（字典序较小的 MPI 为幸存者）。
     */
    @Test
    void approvalMergesBothSidesAtomicallyWithStablePseudonyms() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = new ReviewService(index, new ReviewQueue(100),
                new ReviewStagingStore(), pseudonymizer, new AuditLog());
        String mpiA = index.upsert(singleId("HOSPITAL_A", "7301", "Zhang", "Wei",
                "1990-05-05")).mpiId();
        String mpiB = index.upsert(singleId("HOSPITAL_B", "8301", "Zhang", "Wei",
                "1990-05-05")).mpiId();
        NormalizedPatient conflict = linkingMessage("Zhang", "Wei", "2001-09-09",
                "7301", "8301");
        submitConflict(index, service, conflict, "source-a");
        ReviewTask task = service.listOpen("reviewer").get(0);
        String survivor = List.of(mpiA, mpiB).stream().min(String::compareTo).orElseThrow();

        ReviewService.DecisionResult result = service.decide(task.getId(), true, "reviewer");
        assertEquals("APPROVED", result.decision());
        assertTrue(result.applied());
        log.info("[TEST BASIS] approved with deterministic plan={}", task.getFieldMergePlan());

        MasterPatientRecord byA = index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_A", "7301"));
        MasterPatientRecord byB = index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_B", "8301"));
        assertNotNull(byA);
        assertEquals(byA.getMpiId(), byB.getMpiId(),
                "both identifiers must resolve to one MPI after approval");
        assertEquals(survivor, byA.getMpiId());
        assertEquals(1, index.recordCount());
        assertEquals(2, index.identifierCount());
        assertEquals(2, byA.getIdentifiers().size());
        assertEquals(pseudonymizer.pseudonym(survivor), pseudonymizer.pseudonym(byA.getMpiId()),
                "survivor pseudonym remains stable");
        // 冲突的出生日期永远以幸存者/已有值为准，来件值不得覆盖。
        assertEquals("1990-05-05", byA.getBirthDate());
        assertEquals("Zhang", byA.getFamilyName());

        // 同一种裁决重复调用只生效一次，返回相同结果。
        ReviewService.DecisionResult repeat = service.decide(task.getId(), true, "reviewer");
        assertFalse(repeat.applied());
        assertEquals("APPROVED", repeat.decision());
        assertEquals(1, index.recordCount());
        log.info("[TEST BASIS] repeated approval is idempotent; survivor={}", byA.getMpiId());
    }

    /**
     * 并发穿插：重复冲突提交 + 并发裁决同时进行，
     * 不得出现重复主索引、半合并、覆盖或“拒绝了却改了数据”。
     */
    @Test
    void concurrentSubmissionsAndDecisionsStayConsistent() throws Exception {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = new ReviewService(index, new ReviewQueue(100),
                new ReviewStagingStore(), pseudonymizer, new AuditLog());
        String mpiA = index.upsert(singleId("HOSPITAL_A", "7401", "Zhang", "Wei",
                "1990-05-05")).mpiId();
        String mpiB = index.upsert(singleId("HOSPITAL_B", "8401", "Zhang", "Wei",
                "1990-05-05")).mpiId();
        NormalizedPatient conflict = linkingMessage("Zhang", "Wei", "2001-09-09",
                "7401", "8401");
        submitConflict(index, service, conflict, "source-a");
        String reviewId = service.listOpen("reviewer").get(0).getId();

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> decisionOutcomes = ConcurrentHashMap.newKeySet();
        Set<String> rejectionReasons = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            final int seed = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (seed % 2 == 0) {
                        ReviewService.DecisionResult r = service.decide(reviewId, true, "reviewer");
                        decisionOutcomes.add(r.decision());
                    } else {
                        try {
                            index.upsert(conflict,
                                    c -> service.registerConflict(c, conflict, "source-x"));
                        } catch (IngestionException expected) {
                            rejectionReasons.add(expected.getReason().name());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        log.info("[TEST INPUT] {} threads interleaving repeated conflict submissions and APPROVE",
                threads);
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(Set.of("APPROVED"), decisionOutcomes,
                "all concurrent decisions must observe the same single approval");
        // 裁决前去重为 CONTRADICTORY_DATA；裁决后原冲突消息再次提交仍是冲突
        // （已有生日 1990 与来件 2001 不一致），会新开待办而不是静默合并。
        for (String reason : rejectionReasons) {
            assertEquals("CONTRADICTORY_DATA", reason);
        }

        MasterPatientRecord byA = index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_A", "7401"));
        MasterPatientRecord byB = index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_B", "8401"));
        assertNotNull(byA);
        assertNotNull(byB);
        assertEquals(byA.getMpiId(), byB.getMpiId(), "final view must be a single MPI");
        assertEquals(1, index.recordCount(), "no duplicate MPI may appear");
        assertEquals(2, index.identifierCount(), "identifier ownership stays consistent");
        assertEquals("Zhang", byA.getFamilyName());
        assertEquals("1990-05-05", byA.getBirthDate(), "survivor value must never be overwritten");
        assertEquals(List.of(mpiA, mpiB).stream().min(String::compareTo).orElseThrow(),
                byA.getMpiId());
        log.info("[TEST BASIS] consistent final view: survivor={} identifiers={} records={}",
                byA.getMpiId(), byA.getIdentifierKeys().size(), index.recordCount());
    }

    /** 驳回并发：只生效一次，所有调用方拿到相同结果，索引零改动。 */
    @Test
    void concurrentRejectionAppliesOnceAndLeavesIndexUntouched() throws Exception {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = new ReviewService(index, new ReviewQueue(100),
                new ReviewStagingStore(), pseudonymizer, new AuditLog());
        String mpiA = index.upsert(singleId("HOSPITAL_A", "7501", "Zhang", "Wei",
                "1990-05-05")).mpiId();
        String mpiB = index.upsert(singleId("HOSPITAL_B", "8501", "Zhang", "Wei",
                "1990-05-05")).mpiId();
        submitConflict(index, service,
                linkingMessage("Zhang", "Wei", "2003-03-03", "7501", "8501"), "source-a");
        String reviewId = service.listOpen("reviewer").get(0).getId();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> outcomes = ConcurrentHashMap.newKeySet();
        int[] appliedCount = new int[1];
        Object lock = new Object();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ReviewService.DecisionResult r = service.decide(reviewId, false, "reviewer");
                    outcomes.add(r.decision());
                    if (r.applied()) {
                        synchronized (lock) {
                            appliedCount[0]++;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(Set.of("REJECTED"), outcomes);
        assertEquals(1, appliedCount[0], "the reject decision must apply exactly once");
        assertPatientsUntouched(index, "7501", "8501", mpiA, mpiB, "Zhang", "1990-05-05");
        log.info("[TEST BASIS] concurrent rejects applied once; two original MPIs intact");
    }
}
