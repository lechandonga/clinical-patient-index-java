package com.github.lechandonga.clinicalpatientindexjava.tests.empi;

import com.github.lechandonga.clinicalpatientindexjava.domain.Gender;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasterPatientIndexTest {

    private static final Logger log = LoggerFactory.getLogger(MasterPatientIndexTest.class);

    private NormalizedPatient patient(String idA, String idB, String family, String birthDate) {
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
        p.setGender(Gender.MALE);
        return p;
    }

    @Test
    void mergeIsIdempotentAcrossCasePrefixAndWhitespaceVariants() {
        MasterPatientIndex index = new MasterPatientIndex();
        String first = index.upsert(patient("MRN-1001", "1002", "Zhang", "1990-06-15")).mpiId();
        log.info("[TEST INPUT] first ingest ids=MRN-1001,1002 -> [TEST BASIS] mpiId={}", first);

        NormalizedPatient variant = new NormalizedPatient();
        variant.getIdentifiers().add(new PatientIdentifier("hospital_a", "  mrn1001 "));
        variant.getIdentifiers().add(new PatientIdentifier("HOSPITAL_B", "PAT#1002"));
        variant.setFamilyName("Zhang");
        variant.setGivenName("San");
        variant.setBirthDate("1990-06-15");
        variant.setGender(Gender.MALE);
        String second = index.upsert(variant).mpiId();
        log.info("[TEST INPUT] variant case/prefix/whitespace of same ids -> [TEST BASIS] mpiId={}", second);

        assertEquals(first, second);
        assertEquals(1, index.recordCount());
        assertEquals(2, index.identifierCount());
        MasterPatientIndex.MergeResult repeat =
                index.upsert(patient("MRN-1001", "1002", "Zhang", "1990-06-15"));
        assertFalse(repeat.created());
        assertEquals(first, repeat.mpiId());
        assertEquals(1, index.recordCount());
    }

    @Test
    void differentPatientsAreNotMerged() {
        MasterPatientIndex index = new MasterPatientIndex();
        String a = index.upsert(patient("2001", null, "Li", "1985-01-01")).mpiId();
        String b = index.upsert(patient("2002", null, "Wang", "1972-12-31")).mpiId();
        log.info("[TEST INPUT] two unrelated patients -> [TEST BASIS] {} != {}", a, b);
        assertFalse(a.equals(b));
        assertEquals(2, index.recordCount());
    }

    @Test
    void contradictoryDataBlocksErroneousMerge() {
        MasterPatientIndex index = new MasterPatientIndex();
        index.upsert(patient("3001", "3002", "Zhang", "1990-06-15"));
        NormalizedPatient conflicting = patient(null, "3002", "Other", "2005-01-01");
        log.info("[TEST INPUT] shared id 3002 but birthDate 1990-06-15 vs 2005-01-01");
        IngestionException ex = assertThrows(IngestionException.class, () -> index.upsert(conflicting));
        log.info("[TEST BASIS] rejection reason={} detail={}", ex.getReason(), ex.getMessage());
        assertEquals(IngestionException.RejectionReason.CONTRADICTORY_DATA, ex.getReason());
        assertEquals(1, index.recordCount());
        assertEquals(2, index.identifierCount());
    }

    @Test
    void concurrentAndDuplicateSubmissionsProduceSingleMpi() throws Exception {
        MasterPatientIndex index = new MasterPatientIndex();
        int threads = 32;
        int perThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> mpiIds = ConcurrentHashMap.newKeySet();

        for (int t = 0; t < threads; t++) {
            final int seed = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        NormalizedPatient p = new NormalizedPatient();
                        if (seed % 2 == 0) {
                            p.getIdentifiers().add(new PatientIdentifier("HOSPITAL_A", "CONC-5001"));
                        }
                        p.getIdentifiers().add(new PatientIdentifier("HOSPITAL_B",
                                i % 2 == 0 ? "MRN 5002" : "mrn:5002"));
                        p.setFamilyName("Chen");
                        p.setGivenName("Yi");
                        p.setBirthDate("1999-09-09");
                        p.setGender(Gender.FEMALE);
                        mpiIds.add(index.upsert(p).mpiId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        log.info("[TEST INPUT] {} threads x {} concurrent duplicate/overlapping submissions",
                threads, perThread);
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();
        log.info("[TEST BASIS] distinct MPI ids produced={}, totalRecords={}, totalIdentifiers={}",
                mpiIds.size(), index.recordCount(), index.identifierCount());
        assertEquals(1, mpiIds.size(), "all concurrent submissions must resolve to one MPI");
        assertEquals(1, index.recordCount());
        assertEquals(2, index.identifierCount());
    }
}
