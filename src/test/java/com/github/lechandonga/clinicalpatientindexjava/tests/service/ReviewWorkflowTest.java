package com.github.lechandonga.clinicalpatientindexjava.tests.service;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditLog;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewWorkflowTest {

    private static final Logger log = LoggerFactory.getLogger(ReviewWorkflowTest.class);

    private final Pseudonymizer pseudonymizer =
            new Pseudonymizer("unit-test-secret");

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

    private NormalizedPatient twoIdPatient(String aValue, String bValue,
                                           String family, String birthDate) {
        NormalizedPatient p = patient("HOSPITAL_A", aValue, family, birthDate);
        p.getIdentifiers().add(new PatientIdentifier("HOSPITAL_B", bValue));
        return p;
    }

    /** 用两个已经分属不同簇的标识把两个簇链接起来。 */
    private NormalizedPatient linkingMessage(String idFromClusterA, String idFromClusterB,
                                             String family, String birthDate) {
        NormalizedPatient p = new NormalizedPatient();
        p.getIdentifiers().add(new PatientIdentifier("HOSPITAL_A", idFromClusterA));
        p.getIdentifiers().add(new PatientIdentifier("HOSPITAL_C", idFromClusterB));
        p.setFamilyName(family);
        p.setGivenName("Tester");
        p.setBirthDate(birthDate);
        p.setGender(Gender.FEMALE);
        return p;
    }

    private IngestionException conflictIngest(MasterPatientIndex index,
                                              ReviewService service,
                                              NormalizedPatient message,
                                              String actor) {
        return assertThrows(IngestionException.class,
                () -> index.upsert(message, c -> service.registerConflict(c, message, actor)));
    }

    @Test
    void conflictOpensTaskIsDeduplicatedAndRejectionHasNoSideEffects() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = new ReviewService(index, new ReviewQueue(100),
                new ReviewStagingStore(), pseudonymizer, new AuditLog());
        String mpiA = index.upsert(twoIdPatient("1001", "2001", "Zhang", "1990-01-01")).mpiId();
        String mpiB = index.upsert(patient("HOSPITAL_C", "3001", "Wang", "1980-12-31")).mpiId();

        NormalizedPatient conflict = linkingMessage("1001", "3001", "Wang", "1980-12-31");
        log.info("[TEST INPUT] conflicting message links two independent clusters");
        IngestionException ex = conflictIngest(index, service, conflict, "source-a");
        assertEquals(IngestionException.RejectionReason.CONTRADICTORY_DATA, ex.getReason());
        log.info("[TEST BASIS] rejected as before, reason={} (external behavior unchanged)",
                ex.getReason());

        // 拒绝零副作用：两个簇仍独立，归属关系与人口学数据不变。
        assertEquals(2, index.recordCount());
        assertEquals(3, index.identifierCount());
        assertEquals(mpiA, index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_B", "2001")).getMpiId());
        assertEquals(mpiB, index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_C", "3001")).getMpiId());

        List<ReviewTask> open = service.listOpen("reviewer");
        assertEquals(1, open.size());
        ReviewTask task = open.get(0);
        log.info("[TEST BASIS] task={} mpiIds={} type={} submittedBy={}",
                task.getId(), task.getMpiIds(), task.getConflictType(), task.getSubmittedBy());
        assertEquals(List.of(mpiA, mpiB), task.getMpiIds());
        assertEquals(2, task.getPseudonyms().size());
        assertEquals("source-a", task.getSubmittedBy());
        assertNotNull(task.getSubmittedAt());
        assertTrue(task.getFieldMergePlan().size() >= 3,
                "merge plan must document deterministic field resolution");

        conflictIngest(index, service, conflict, "source-b");
        conflictIngest(index, service, conflict, "source-a");
        List<ReviewTask> stillOpen = service.listOpen("reviewer");
        assertEquals(1, stillOpen.size());
        assertEquals(3, stillOpen.get(0).getSubmissionCount());
        log.info("[TEST BASIS] dedup holds, submissionCount={} openTasks={}",
                stillOpen.get(0).getSubmissionCount(), stillOpen.size());
    }

    @Test
    void queueFullProducesDistinctReasonAndDoesNotDropSilently() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = new ReviewService(index, new ReviewQueue(1),
                new ReviewStagingStore(), pseudonymizer, new AuditLog());
        index.upsert(twoIdPatient("1", "2", "Li", "1970-01-01"));
        index.upsert(patient("HOSPITAL_C", "3", "Wang", "1971-01-01"));
        index.upsert(patient("HOSPITAL_D", "4", "Chen", "1960-06-06"));
        NormalizedPatient firstConflict = linkingMessage("1", "3", "Wang", "1971-01-01");
        conflictIngest(index, service, firstConflict, "source-a");
        assertEquals(1, service.listOpen("reviewer").size());

        NormalizedPatient secondConflict = new NormalizedPatient();
        secondConflict.getIdentifiers().add(new PatientIdentifier("HOSPITAL_A", "1"));
        secondConflict.getIdentifiers().add(new PatientIdentifier("HOSPITAL_D", "4"));
        secondConflict.setFamilyName("Chen");
        secondConflict.setGivenName("Tester");
        secondConflict.setBirthDate("1960-06-06");
        secondConflict.setGender(Gender.FEMALE);
        log.info("[TEST INPUT] second conflict pair while queue limit=1");
        IngestionException ex = conflictIngest(index, service, secondConflict, "source-b");
        log.info("[TEST BASIS] rejection reason={}", ex.getReason());
        assertEquals(IngestionException.RejectionReason.REVIEW_QUEUE_FULL, ex.getReason());
        assertEquals(1, service.listOpen("reviewer").size(),
                "full-queue conflict must not be silently stored");
        assertEquals(3, index.recordCount());
        assertEquals(4, index.identifierCount());
    }

    @Test
    void approveMergesClustersAtomicallyAndIsIdempotentUnderConcurrency() throws Exception {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = new ReviewService(index, new ReviewQueue(100),
                new ReviewStagingStore(), pseudonymizer, new AuditLog());
        String mpiA = index.upsert(twoIdPatient("1001", "2001", "Zhang", "1990-01-01")).mpiId();
        String mpiB = index.upsert(patient("HOSPITAL_C", "3001", "Wang", "1980-12-31")).mpiId();
        NormalizedPatient conflict = linkingMessage("1001", "3001", "Wang", "1980-12-31");
        conflictIngest(index, service, conflict, "source-a");
        String reviewId = service.listOpen("reviewer").get(0).getId();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> decisions = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ReviewService.DecisionResult result =
                            service.decide(reviewId, true, "reviewer");
                    decisions.add(result.decision());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        log.info("[TEST INPUT] {} concurrent APPROVE decisions on the same task", threads);
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        ReviewService.DecisionResult repeated = service.decide(reviewId, true, "reviewer");
        log.info("[TEST BASIS] decisions={} repeatApplied={} openTasks={}",
                decisions, repeated.applied(), service.listOpen("reviewer").size());
        assertEquals(Set.of("APPROVED"), decisions,
                "concurrent callers must all observe the same single approval result");
        assertEquals(false, repeated.applied(), "repeated decision must not apply twice");
        assertEquals("APPROVED", repeated.decision());

        MasterPatientRecord byA =
                index.findByIdentifier(new PatientIdentifier("HOSPITAL_A", "1001"));
        MasterPatientRecord byC =
                index.findByIdentifier(new PatientIdentifier("HOSPITAL_C", "3001"));
        assertNotNull(byA);
        assertEquals(byA.getMpiId(), byC.getMpiId(),
                "both sides' identifiers must resolve to one MPI after approval");
        assertEquals(1, index.recordCount());
        String survivor = List.of(mpiA, mpiB).stream().min(String::compareTo).orElseThrow();
        assertEquals(survivor, byA.getMpiId(), "lower MPI id is the deterministic survivor");
        assertEquals(pseudonymizer.pseudonym(survivor),
                pseudonymizer.pseudonym(byA.getMpiId()), "survivor pseudonym stays stable");
        assertEquals("Zhang", byA.getFamilyName(), "survivor demographics win");
        assertEquals("1990-01-01", byA.getBirthDate());
        assertEquals(3, byA.getIdentifiers().size());
    }

    @Test
    void rejectLeavesEverythingUntouched() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = new ReviewService(index, new ReviewQueue(100),
                new ReviewStagingStore(), pseudonymizer, new AuditLog());
        String mpiA = index.upsert(patient("HOSPITAL_A", "5001", "Sun", "1985-05-05")).mpiId();
        NormalizedPatient conflict = patient("HOSPITAL_A", "mrn5001", "Moon", "2000-01-01");
        conflictIngest(index, service, conflict, "source-a");
        ReviewTask task = service.listOpen("reviewer").get(0);

        ReviewService.DecisionResult rejected = service.decide(task.getId(), false, "reviewer");
        log.info("[TEST BASIS] reject decision={} applied={}", rejected.decision(), rejected.applied());
        assertEquals("REJECTED", rejected.decision());
        assertEquals(1, index.recordCount());
        MasterPatientRecord record =
                index.findByIdentifier(new PatientIdentifier("HOSPITAL_A", "5001"));
        assertEquals(mpiA, record.getMpiId());
        assertEquals("Sun", record.getFamilyName(), "demographics must be unchanged");
        assertEquals("1985-05-05", record.getBirthDate());
        assertEquals(1, index.identifierCount(), "no new identifier ownership is created");

        ReviewService.DecisionResult repeat = service.decide(task.getId(), false, "reviewer");
        assertEquals(false, repeat.applied());
        assertEquals("REJECTED", repeat.decision());
        assertEquals(0, service.listOpen("reviewer").size());
    }

    @Test
    void approveSingleClusterConflictFillsButNeverOverwrites() {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = new ReviewService(index, new ReviewQueue(100),
                new ReviewStagingStore(), pseudonymizer, new AuditLog());
        NormalizedPatient base = patient("HOSPITAL_A", "6001", "Lin", "1977-07-07");
        base.setGender(null);
        base.setGivenName(null);
        String mpi = index.upsert(base).mpiId();
        NormalizedPatient conflict = patient("HOSPITAL_A", "6001", "Lan", "1977-07-07");
        conflict.getIdentifiers().add(new PatientIdentifier("HOSPITAL_B", "6002"));
        conflict.setGivenName("Fillme");
        conflict.setGender(Gender.MALE);
        conflictIngest(index, service, conflict, "source-b");
        String reviewId = service.listOpen("reviewer").get(0).getId();

        ReviewService.DecisionResult result = service.decide(reviewId, true, "reviewer");
        log.info("[TEST BASIS] single-cluster approve decision={} basis={}",
                result.decision(), result.basis());
        assertEquals("APPROVED", result.decision());
        MasterPatientRecord record = index.findByMpiId(mpi);
        assertEquals(mpi, record.getMpiId());
        assertEquals("Lin", record.getFamilyName(), "existing family name wins");
        assertEquals("Fillme", record.getGivenName(), "empty given name is filled");
        assertEquals(Gender.MALE, record.getGender(), "empty gender is filled");
        assertEquals(mpi, index.findByIdentifier(
                new PatientIdentifier("HOSPITAL_B", "6002")).getMpiId(),
                "new identifier is linked after approval");
        assertEquals(2, index.identifierCount());
    }

    @Test
    void interleavedIngestionDuringDecisionNeverDuplicatesOrHalfMerges() throws Exception {
        MasterPatientIndex index = new MasterPatientIndex();
        ReviewService service = new ReviewService(index, new ReviewQueue(100),
                new ReviewStagingStore(), pseudonymizer, new AuditLog());
        index.upsert(twoIdPatient("1001", "2001", "Zhang", "1990-01-01"));
        index.upsert(patient("HOSPITAL_C", "3001", "Wang", "1980-12-31"));
        NormalizedPatient conflict = linkingMessage("1001", "3001", "Wang", "1980-12-31");
        conflictIngest(index, service, conflict, "source-a");
        String reviewId = service.listOpen("reviewer").get(0).getId();

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int seed = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (seed % 3 == 0) {
                        service.decide(reviewId, true, "reviewer");
                    } else {
                        // 穿插：重复冲突提交（可能去重或因已合并且生日不同再冲突）
                        // 与无关注入，都不得产生重复主索引或破坏归属。
                        NormalizedPatient repeat =
                                linkingMessage("1001", "3001", "Wang", "1980-12-31");
                        try {
                            index.upsert(repeat,
                                    c -> service.registerConflict(c, repeat, "source-x"));
                        } catch (IngestionException expected) {
                            // 裁决前 -> 冲突去重；裁决后同生日消息可直接幂等合并。
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        log.info("[TEST INPUT] {} threads interleaving APPROVE and repeated conflict ingest",
                threads);
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        MasterPatientRecord a =
                index.findByIdentifier(new PatientIdentifier("HOSPITAL_A", "1001"));
        MasterPatientRecord c =
                index.findByIdentifier(new PatientIdentifier("HOSPITAL_C", "3001"));
        assertNotNull(a);
        assertNotNull(c);
        assertEquals(a.getMpiId(), c.getMpiId(), "final state must be a single merged MPI");
        assertEquals(1, index.recordCount(), "no duplicate MPI may appear");
        assertEquals(3, index.identifierCount(), "identifier ownership stays consistent");
        assertEquals("Zhang", a.getFamilyName());
        assertEquals("1990-01-01", a.getBirthDate(), "survivor value must not be overwritten");
        log.info("[TEST BASIS] final survivor={} identifiers={} records={}",
                a.getMpiId(), a.getIdentifierKeys().size(), index.recordCount());
    }
}
