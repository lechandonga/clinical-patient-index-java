package com.github.lechandonga.clinicalpatientindexjava.empi;

import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException.RejectionReason;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 并发安全的本地患者主索引。
 *
 * 归并规则：
 * <ul>
 *   <li>标识符 canonical key 相同（忽略大小写、空白、常见前缀）视为同一人；</li>
 *   <li>一次提交携带多个标识符时，这些标识符互相链接（union-find）；</li>
 *   <li>两个已存在簇通过同一标识符链接时，若核心人口学（出生日期）冲突则拒绝，
 *       防止不同患者被误合并。</li>
 * </ul>
 * 单把归并锁保证：重复主索引不会产生、更新不会丢失、不存在半合并状态。
 */
public class MasterPatientIndex {

    private final ReentrantLock mergeLock = new ReentrantLock();
    private final Map<String, MasterPatientRecord> records = new ConcurrentHashMap<>();
    private final Map<String, String> identifierToMpi = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public record MergeResult(String mpiId, boolean created, boolean merged, List<String> basis) {
    }

    public MergeResult upsert(NormalizedPatient patient) {
        mergeLock.lock();
        try {
            List<String> basis = new ArrayList<>();
            MasterPatientRecord target = null;

            for (PatientIdentifier identifier : patient.getIdentifiers()) {
                String key = identifier.canonicalKey();
                String existingMpi = identifierToMpi.get(key);
                if (existingMpi == null) {
                    continue;
                }
                MasterPatientRecord existing = records.get(existingMpi);
                if (target == null) {
                    target = existing;
                    basis.add("matched identifier " + key);
                } else if (!existing.getMpiId().equals(target.getMpiId())) {
                    assertCompatible(target, existing, key);
                    target = mergeInto(target, existing, basis, key);
                }
            }

            boolean created = false;
            boolean merged = target != null && !basis.isEmpty();
            if (target == null) {
                target = createRecord();
                created = true;
                basis.add("no matching identifier; created new MPI");
            } else {
                for (PatientIdentifier identifier : patient.getIdentifiers()) {
                    String mpi = identifierToMpi.get(identifier.canonicalKey());
                    if (mpi != null && mpi.equals(target.getMpiId())) {
                        assertIncomingCompatible(target, patient, identifier.canonicalKey());
                        break;
                    }
                }
            }

            for (PatientIdentifier identifier : patient.getIdentifiers()) {
                String previous = identifierToMpi.putIfAbsent(identifier.canonicalKey(), target.getMpiId());
                if (previous == null || previous.equals(target.getMpiId())) {
                    target.addIdentifier(identifier);
                    if (previous == null) {
                        basis.add("linked identifier " + identifier.canonicalKey());
                    } else {
                        basis.add("identifier already known (idempotent) " + identifier.canonicalKey());
                    }
                }
            }
            updateDemographics(target, patient, basis);
            return new MergeResult(target.getMpiId(), created, merged, List.copyOf(basis));
        } finally {
            mergeLock.unlock();
        }
    }

    public MasterPatientRecord findByIdentifier(PatientIdentifier identifier) {
        String mpiId = identifierToMpi.get(identifier.canonicalKey());
        return mpiId == null ? null : records.get(mpiId);
    }

    public MasterPatientRecord findByMpiId(String mpiId) {
        return records.get(mpiId);
    }

    public int recordCount() {
        return records.size();
    }

    public int identifierCount() {
        return identifierToMpi.size();
    }

    private MasterPatientRecord createRecord() {
        String mpiId = String.format("MPI-%010d", sequence.incrementAndGet());
        MasterPatientRecord record = new MasterPatientRecord(mpiId);
        records.put(mpiId, record);
        return record;
    }

    private static void assertCompatible(MasterPatientRecord a, MasterPatientRecord b, String linkKey) {
        if (a.getBirthDate() != null && b.getBirthDate() != null
                && !a.getBirthDate().equals(b.getBirthDate())) {
            throw new IngestionException(RejectionReason.CONTRADICTORY_DATA,
                    "refusing merge: identifier " + linkKey + " links records with conflicting birth dates ("
                            + a.getMpiId() + " " + a.getBirthDate() + " vs "
                            + b.getMpiId() + " " + b.getBirthDate() + ")");
        }
        if (a.getFamilyName() != null && b.getFamilyName() != null
                && !a.getFamilyName().equalsIgnoreCase(b.getFamilyName())
                && a.getBirthDate() != null && a.getBirthDate().equals(b.getBirthDate())) {
            throw new IngestionException(RejectionReason.CONTRADICTORY_DATA,
                    "refusing merge: identifier " + linkKey + " links records with same birth date "
                            + "but conflicting family names");
        }
    }

    private static void assertIncomingCompatible(MasterPatientRecord record,
                                                 NormalizedPatient incoming,
                                                 String matchedKey) {
        if (record.getBirthDate() != null && incoming.getBirthDate() != null
                && !record.getBirthDate().equals(incoming.getBirthDate())) {
            throw new IngestionException(RejectionReason.CONTRADICTORY_DATA,
                    "refusing update: identifier " + matchedKey + " resolves to "
                            + record.getMpiId() + " with birthDate " + record.getBirthDate()
                            + " but incoming birthDate is " + incoming.getBirthDate());
        }
        if (record.getFamilyName() != null && incoming.getFamilyName() != null
                && !record.getFamilyName().equalsIgnoreCase(incoming.getFamilyName())) {
            throw new IngestionException(RejectionReason.CONTRADICTORY_DATA,
                    "refusing update: identifier " + matchedKey + " resolves to "
                            + record.getMpiId() + " family '" + record.getFamilyName()
                            + "' but incoming family is '" + incoming.getFamilyName() + "'");
        }
    }

    private MasterPatientRecord mergeInto(MasterPatientRecord survivor,
                                          MasterPatientRecord absorbed,
                                          List<String> basis,
                                          String linkKey) {
        for (String key : absorbed.getIdentifierKeys()) {
            identifierToMpi.put(key, survivor.getMpiId());
        }
        for (PatientIdentifier identifier : absorbed.getIdentifiers()) {
            survivor.addIdentifier(identifier);
        }
        if (survivor.getFamilyName() == null) {
            survivor.setFamilyName(absorbed.getFamilyName());
        }
        if (survivor.getGivenName() == null) {
            survivor.setGivenName(absorbed.getGivenName());
        }
        if (survivor.getBirthDate() == null) {
            survivor.setBirthDate(absorbed.getBirthDate());
        }
        if (survivor.getGender() == null) {
            survivor.setGender(absorbed.getGender());
        }
        records.remove(absorbed.getMpiId());
        basis.add("merged " + absorbed.getMpiId() + " into " + survivor.getMpiId()
                + " via shared identifier " + linkKey);
        return survivor;
    }

    private static void updateDemographics(MasterPatientRecord record,
                                           NormalizedPatient patient,
                                           List<String> basis) {
        if (record.getFamilyName() == null && patient.getFamilyName() != null) {
            record.setFamilyName(patient.getFamilyName());
        }
        if (record.getGivenName() == null && patient.getGivenName() != null) {
            record.setGivenName(patient.getGivenName());
        }
        if (record.getBirthDate() == null && patient.getBirthDate() != null) {
            record.setBirthDate(patient.getBirthDate());
        }
        if (record.getGender() == null && patient.getGender() != null) {
            record.setGender(patient.getGender());
        }
        if (record.getBirthDate() != null && patient.getBirthDate() != null
                && !record.getBirthDate().equals(patient.getBirthDate())) {
            basis.add("kept existing birthDate " + record.getBirthDate()
                    + " (incoming " + patient.getBirthDate() + " ignored as conflicting)");
        }
    }
}
