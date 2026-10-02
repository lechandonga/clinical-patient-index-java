package com.github.lechandonga.clinicalpatientindexjava.tests.empi;

import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.domain.Gender;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex.FieldDecision;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex.UpsertPlan;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
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
 * 预检只读性、冲突规划、强制合并裁决在主索引层面的单测。
 */
class PlanAndReviewIndexTest {

    private static final Logger log = LoggerFactory.getLogger(PlanAndReviewIndexTest.class);

    private NormalizedPatient patient(String idA, String idB, String family,
                                      String birthDate, Gender gender) {
        NormalizedPatient p = new NormalizedPatient();
        if (idA != null) {
            p.getIdentifiers().add(new PatientIdentifier("HOSPITAL_A", idA));
        }
        if (idB != null) {
            p.getIdentifiers().add(new PatientIdentifier("HOSPITAL_B", idB));
        }
        p.setFamilyName(family);
        p.setGivenName("San");
        p.setBirthDate(birthDate);
        p.setGender(gender);
        return p;
    }

    private Map<String, String> snapshot(MasterPatientIndex index) {
        Map<String, String> snapshot = new ConcurrentHashMap<>();
        snapshot.put("records", String.valueOf(index.recordCount()));
        snapshot.put("identifiers", String.valueOf(index.identifierCount()));
        return snapshot;
    }

    @Test
    void precheckIsReadOnlyForCreateUpdateAndRejectedMessages() {
        MasterPatientIndex index = new MasterPatientIndex();
        NormalizedPatient seeded = patient("9001", null, "Zhao", "1988-03-20", Gender.FEMALE);
        seeded.setGivenName(null);
        index.upsert(seeded);

        NormalizedPatient newPatient = patient("9051", "9052", "Qian", "1977-07-07", Gender.MALE);
        NormalizedPatient updating = new NormalizedPatient();
        updating.getIdentifiers().add(new PatientIdentifier("HOSPITAL_A", "9001"));
        updating.getIdentifiers().add(new PatientIdentifier("HOSPITAL_B", "9002"));
        updating.setFamilyName("Zhao");
        updating.setGivenName("Xin");
        updating.setBirthDate("1988-03-20");
        updating.setGender(Gender.FEMALE);

        log.info("[TEST INPUT] precheck create-new message (not yet submitted)");
        Map<String, String> before = snapshot(index);
        UpsertPlan createPlan = index.planUpsert(newPatient);
        UpsertPlan createPlanAgain = index.planUpsert(newPatient);
        log.info("[TEST BASIS] create plan outcome createNew={} fields={}",
                createPlan.createNew(), createPlan.fieldDecisions().stream().map(FieldDecision::field).toList());
        assertTrue(createPlan.createNew());
        assertEquals(createPlan.basis(), createPlanAgain.basis(),
                "repeated precheck of same message must yield identical result");
        assertEquals(before, snapshot(index), "precheck must not change index state");

        log.info("[TEST INPUT] precheck message matching existing MPI with a new id");
        UpsertPlan updatePlan = index.planUpsert(updating);
        assertFalse(updatePlan.createNew());
        assertTrue(updatePlan.identifierDecisions().stream()
                .anyMatch(d -> d.effect().equals("LINK_TO_EXISTING")));
        assertTrue(updatePlan.fieldDecisions().stream()
                .anyMatch(d -> d.field().equals("givenName") && d.effect().equals("FILL_FROM_INCOMING")));
        assertEquals(before, snapshot(index), "precheck must not attach identifiers or change fields");

        NormalizedPatient rejected = patient("9001", null, "OtherFamily", "2000-01-01", Gender.FEMALE);
        log.info("[TEST INPUT] precheck message that formal ingest would reject with conflict");
        assertThrows(IngestionException.class, () -> index.planUpsert(rejected));
        assertEquals(before, snapshot(index), "precheck of a conflicting message must be read-only");
        assertThrows(IngestionException.class, () -> index.planUpsert(rejected));
        assertEquals(before, snapshot(index));
    }

    @Test
    void forceMergeApprovalIsAtomicAndLinksBothSidesIdentifiers() {
        MasterPatientIndex index = new MasterPatientIndex();
        index.upsert(patient("9201", null, "Zhao", "1988-03-20", Gender.FEMALE));
        index.upsert(patient("9301", null, "Wang", "1988-03-20", null));
        String a = index.findByIdentifier(new PatientIdentifier("HOSPITAL_A", "9201")).getMpiId();
        String b = index.findByIdentifier(new PatientIdentifier("HOSPITAL_A", "9301")).getMpiId();
        log.info("[TEST INPUT] two separate clusters {} (Zhao, FEMALE) and {} (Wang, null gender)", a, b);

        java.util.List<FieldDecision> decisions = index.forceMerge(a, b);
        log.info("[TEST BASIS] forced merge decisions={}",
                decisions.stream().map(d -> d.field() + "=" + d.effect() + "(" + d.source() + ")").toList());
        assertEquals(1, index.recordCount());
        assertEquals(a, index.findByIdentifier(new PatientIdentifier("HOSPITAL_A", "9301")).getMpiId(),
                "absorbed side identifiers must resolve to the survivor MPI after approval");
        assertEquals(a, index.findByIdentifier(new PatientIdentifier("HOSPITAL_A", "9201")).getMpiId());
        FieldDecision family = decisions.stream().filter(d -> d.field().equals("familyName")).findFirst().orElseThrow();
        FieldDecision gender = decisions.stream().filter(d -> d.field().equals("gender")).findFirst().orElseThrow();
        assertEquals("KEEP_EXISTING", family.effect(), "survivor existing value must win");
        assertEquals("Zhao", family.chosenValue());
        assertEquals("KEEP_EXISTING", gender.effect());
    }

    @Test
    void concurrentForceMergeAndIngestNeverProduceHalfState() throws Exception {
        MasterPatientIndex index = new MasterPatientIndex();
        index.upsert(patient("9401", null, "Sun", "1980-05-05", Gender.MALE));
        index.upsert(patient("9501", null, "Li", "1990-06-06", Gender.FEMALE));
        String a = index.findByIdentifier(new PatientIdentifier("HOSPITAL_A", "9401")).getMpiId();
        String b = index.findByIdentifier(new PatientIdentifier("HOSPITAL_A", "9501")).getMpiId();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            pool.submit(() -> {
                try {
                    start.await();
                    if (seed % 2 == 0) {
                        index.forceMerge(a, b);
                    } else {
                        NormalizedPatient more = new NormalizedPatient();
                        more.getIdentifiers().add(new PatientIdentifier("HOSPITAL_B", "ID-" + seed));
                        more.getIdentifiers().add(new PatientIdentifier("HOSPITAL_A", "9401"));
                        more.setFamilyName("Sun");
                        more.setGivenName("G" + seed);
                        more.setBirthDate("1980-05-05");
                        index.upsert(more);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(1, index.recordCount(), "approved merge must never be left half-done");
        assertEquals(a, index.findByIdentifier(new PatientIdentifier("HOSPITAL_A", "9501")).getMpiId());
        assertEquals("Sun",
                index.findByMpiId(a).getFamilyName(), "survivor data must never be overwritten");
        log.info("[TEST BASIS] after concurrent merge+ingest records={} identifiers={}",
                index.recordCount(), index.identifierCount());
    }
}
