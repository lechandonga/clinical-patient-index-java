package com.github.lechandonga.clinicalpatientindexjava.mpi;

import com.github.lechandonga.clinicalpatientindexjava.deid.DeIdentificationService;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedIdentifier;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.ingest.ErrorCode;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IngestException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进程内并发安全的患者主索引（EMPI）。
 * <p>归并规则：仅当两条记录共享至少一个「来源系统 + 规范化标识符」完全相同的键时才归并；
 * 姓名、出生日期等人口学信息只用于补全与冲突告警，<b>绝不</b>单独触发合并，避免误合并。
 * 所有写操作在同一把锁内原子完成（查找-归并-提交），因此重复/并发提交幂等，
 * 不会出现重复主索引、覆盖丢失或半合并状态。
 */
@Component
public class MasterPatientIndex {

    private final DeIdentificationService deIdentificationService;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, MasterRecord> records = new LinkedHashMap<>();
    private final Map<String, String> identifierOwners = new LinkedHashMap<>();

    public MasterPatientIndex(DeIdentificationService deIdentificationService) {
        this.deIdentificationService = deIdentificationService;
    }

    public MergeResult upsert(NormalizedPatient patient) {
        lock.lock();
        try {
            List<String> warnings = new ArrayList<>();
            Set<String> roots = new HashSet<>();
            for (NormalizedIdentifier identifier : patient.getIdentifiers()) {
                String owner = identifierOwners.get(identifier.canonicalKey());
                if (owner != null) {
                    roots.add(owner);
                }
            }

            MasterRecord target;
            int mergedGroups;
            boolean created;
            if (roots.isEmpty()) {
                target = new MasterRecord();
                String firstKey = patient.getIdentifiers().get(0).canonicalKey();
                target.setMasterId(deIdentificationService.pseudonym(firstKey));
                if (records.containsKey(target.getMasterId())) {
                    throw new IngestException(ErrorCode.DUPLICATE_CONFLICT,
                            "主索引伪名发生碰撞，请更换去标识化密钥");
                }
                records.put(target.getMasterId(), target);
                created = true;
                mergedGroups = 0;
            } else {
                List<MasterRecord> existing = roots.stream().map(records::get).toList();
                target = existing.get(0);
                for (int i = 1; i < existing.size(); i++) {
                    absorb(target, existing.get(i), warnings);
                }
                created = false;
                mergedGroups = existing.size() - 1;
            }

            target.mergeDemographics(patient.getFamilyName(), patient.getGivenName(),
                    patient.getBirthDate(), patient.getGender(), patient.getPhone(), warnings);

            for (NormalizedIdentifier identifier : patient.getIdentifiers()) {
                String key = identifier.canonicalKey();
                String owner = identifierOwners.get(key);
                if (owner != null && !owner.equals(target.getMasterId())) {
                    throw new IngestException(ErrorCode.DUPLICATE_CONFLICT,
                            "标识符已属于另一主索引，拒绝半合并: keyDigest="
                                    + NormalizedIdentifierDigest.digestKey(key));
                }
                if (target.getIdentifiers().putIfAbsent(key, identifier) == null) {
                    identifierOwners.put(key, target.getMasterId());
                }
            }
            target.getMergeWarnings().addAll(warnings);
            target.incrementVersion();
            return new MergeResult(target.getMasterId(), created, mergedGroups,
                    Collections.unmodifiableList(new ArrayList<>(warnings)));
        } finally {
            lock.unlock();
        }
    }

    private void absorb(MasterRecord survivor, MasterRecord victim, List<String> warnings) {
        if (victim == survivor) {
            return;
        }
        survivor.mergeDemographics(victim.getFamilyName(), victim.getGivenName(),
                victim.getBirthDate(), victim.getGender(), victim.getPhone(), warnings);
        for (Map.Entry<String, NormalizedIdentifier> entry : victim.getIdentifiers().entrySet()) {
            survivor.getIdentifiers().putIfAbsent(entry.getKey(), entry.getValue());
            identifierOwners.put(entry.getKey(), survivor.getMasterId());
        }
        survivor.getMergeWarnings().addAll(victim.getMergeWarnings());
        records.remove(victim.getMasterId());
    }

    /** 按规范化标识符键查找主记录（内部已持锁/只读快照）。 */
    public MasterRecord findByIdentifier(String system, String normalizedValue) {
        lock.lock();
        try {
            String owner = identifierOwners.get(system + "|" + normalizedValue);
            return owner == null ? null : records.get(owner);
        } finally {
            lock.unlock();
        }
    }

    public MasterRecord requireByIdentifier(String system, String normalizedValue) {
        MasterRecord record = findByIdentifier(system, normalizedValue);
        if (record == null) {
            throw new IngestException(ErrorCode.PATIENT_NOT_FOUND, "未找到匹配的患者主索引");
        }
        return record;
    }

    /** 仅供测试复位。 */
    public void reset() {
        lock.lock();
        try {
            records.clear();
            identifierOwners.clear();
        } finally {
            lock.unlock();
        }
    }

    public int recordCount() {
        lock.lock();
        try {
            return records.size();
        } finally {
            lock.unlock();
        }
    }

    private static final class NormalizedIdentifierDigest {
        private static String digestKey(String key) {
            return DeIdentificationService.fingerprint(key);
        }
    }
}
