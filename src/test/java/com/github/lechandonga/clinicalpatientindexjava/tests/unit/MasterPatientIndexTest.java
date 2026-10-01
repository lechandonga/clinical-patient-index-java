package com.github.lechandonga.clinicalpatientindexjava.tests.unit;

import com.github.lechandonga.clinicalpatientindexjava.config.CpiProperties;
import com.github.lechandonga.clinicalpatientindexjava.deid.DeIdentificationService;
import com.github.lechandonga.clinicalpatientindexjava.domain.Gender;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IdentifierNormalizer;
import com.github.lechandonga.clinicalpatientindexjava.mpi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.mpi.MergeResult;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasterPatientIndexTest {

    private static final Logger log = LoggerFactory.getLogger(MasterPatientIndexTest.class);

    private MasterPatientIndex index;
    private IdentifierNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new IdentifierNormalizer(new CpiProperties());
        index = new MasterPatientIndex(new DeIdentificationService(new CpiProperties()));
    }

    @Test
    void mergeIsIdempotentAndDemographicCompleteDoesNotOverwrite() {
        NormalizedPatient first = patient("HIS", "MRN-001", "张", "三", "1984-03-07", Gender.M);
        MergeResult created = index.upsert(first);
        assertTrue(created.created());

        MergeResult duplicate = index.upsert(patient("his", "mrn001", "张", "三", "1984-03-07", Gender.M));
        assertEquals(false, duplicate.created());
        assertEquals(created.masterId(), duplicate.masterId());
        assertEquals(1, index.recordCount());
        log.info("重复提交判定 inputDigest 两次等价输入 -> masterId 相同={}, 依据=标识符归一后共享 canonicalKey",
                created.masterId().equals(duplicate.masterId()));

        MergeResult withNewSystem = index.upsert(patient("LIS", "P-9", "张", "三", "1984-03-07", Gender.M));
        first.addIdentifier(normalizer.normalize("LIS", "P-9", "MR"));
        MergeResult bridgeAgain = index.upsert(first);
        assertEquals(withNewSystem.masterId(), bridgeAgain.masterId());
        assertEquals(1, index.recordCount());
        log.info("跨来源归并判定 LIS/P-9 与 HIS/MRN-001 经共享记录桥接 -> 唯一主索引 {}", bridgeAgain.masterId());
    }

    @Test
    void differentPatientsAreNeverMergedByDemographics() {
        index.upsert(patient("HIS", "MRN-100", "王", "五", "1990-01-01", Gender.M));
        index.upsert(patient("HIS", "MRN-200", "王", "五", "1990-01-01", Gender.M));
        assertEquals(2, index.recordCount());
        log.info("防误合并判定: 姓名生日完全相同但标识符不同 -> 主索引数=2，依据=人口学字段不触发合并");
    }

    @Test
    void concurrentDuplicateSubmissionsProduceSingleMasterRecord() throws InterruptedException {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> masterIds = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            final int seq = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String rawValue = (seq % 2 == 0 ? "MRN-007" : "mrn007");
                    MergeResult result = index.upsert(patient("HIS", rawValue, "赵", "六", "1977-07-07", Gender.F));
                    masterIds.add(result.masterId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, masterIds.size(), "并发重复提交不得产生多个主索引");
        assertEquals(1, index.recordCount());
        log.info("并发判定: {} 个线程同时提交等价标识符 -> masterId 集合大小={}, 记录数=1", threads, masterIds.size());
    }

    @Test
    void concurrentBridgingMergesTransitiveChainsAtomically() throws InterruptedException {
        index.upsert(patient("HIS", "MRN-A", "钱", "七", "1980-08-08", Gender.F));
        index.upsert(patient("LIS", "LAB-B", "钱", "七", "1980-08-08", Gender.F));

        NormalizedPatient bridge1 = patient("HIS", "MRN-A", "钱", "七", "1980-08-08", Gender.F);
        bridge1.addIdentifier(normalizer.normalize("SHARED", "X-1", "MR"));
        NormalizedPatient bridge2 = patient("LIS", "LAB-B", "钱", "七", "1980-08-08", Gender.F);
        bridge2.addIdentifier(normalizer.normalize("SHARED", "x1", "MR"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        pool.submit(() -> awaitAndUpsert(start, bridge1));
        pool.submit(() -> awaitAndUpsert(start, bridge2));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, index.recordCount(), "传递链并发归并后必须只剩一个主索引，无半合并状态");
        log.info("并发传递归并判定: A<-SHARED->B 两个桥接任务并发 -> 最终主索引数=1");
    }

    private void awaitAndUpsert(CountDownLatch latch, NormalizedPatient patient) {
        try {
            latch.await();
            index.upsert(patient);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private NormalizedPatient patient(String system, String value, String family, String given,
                                      String birthDate, Gender gender) {
        NormalizedPatient patient = new NormalizedPatient();
        patient.addIdentifier(normalizer.normalize(system, value, "MR"));
        patient.setFamilyName(family);
        patient.setGivenName(given);
        patient.setBirthDate(birthDate);
        patient.setGender(gender);
        return patient;
    }
}
