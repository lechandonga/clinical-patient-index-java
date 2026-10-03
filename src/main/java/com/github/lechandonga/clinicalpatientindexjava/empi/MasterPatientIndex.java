package com.github.lechandonga.clinicalpatientindexjava.empi;

import com.github.lechandonga.clinicalpatientindexjava.domain.ConflictException;
import com.github.lechandonga.clinicalpatientindexjava.domain.ConflictException.ConflictType;
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
    private final Map<String, String> mergedAway = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public record MergeResult(String mpiId, boolean created, boolean merged, List<String> basis) {
    }

    public MergeResult upsert(NormalizedPatient patient) {
        return upsert(patient, null);
    }

    /**
     * @param conflictHook 发生人口学冲突时，在归并锁内、抛出前执行的回调
     *                     （用于原子登记人工复核；预检传 null 表示只读，不登记）。
     *                     回调自身可抛出异常以改变拒绝结果（如 REVIEW_QUEUE_FULL）。
     */
    public MergeResult upsert(NormalizedPatient patient,
                              java.util.function.Consumer<ConflictException> conflictHook) {
        mergeLock.lock();
        try {
            try {
                return doUpsert(patient);
            } catch (ConflictException conflict) {
                if (conflictHook != null) {
                    conflictHook.accept(conflict);
                }
                throw conflict;
            }
        } finally {
            mergeLock.unlock();
        }
    }

    private MergeResult doUpsert(NormalizedPatient patient) {
        List<String> basis = new ArrayList<>();
        // 阶段一（只读校验）：解析本消息命中的全部已有簇，做两两人口学校验，
        // 再校验来件人口学与每个命中簇兼容。任何冲突都在写入之前抛出，
        // 因此被拒绝的提交不会移动任何标识、不会合簇、不会改人口学字段。
        List<MasterPatientRecord> matchedClusters = new ArrayList<>();
        for (PatientIdentifier identifier : patient.getIdentifiers()) {
            String key = identifier.canonicalKey();
            String existingMpi = identifierToMpi.get(key);
            if (existingMpi == null) {
                continue;
            }
            MasterPatientRecord existing = records.get(existingMpi);
            if (matchedClusters.isEmpty()) {
                basis.add("matched identifier " + key);
                matchedClusters.add(existing);
            } else if (!existing.getMpiId().equals(matchedClusters.get(0).getMpiId())
                    && matchedClusters.stream().noneMatch(r -> r.getMpiId().equals(existing.getMpiId()))) {
                for (MasterPatientRecord seen : matchedClusters) {
                    assertClustersCompatible(seen, existing, key);
                }
                matchedClusters.add(existing);
            }
        }
        for (MasterPatientRecord matched : matchedClusters) {
            assertIncomingCompatible(matched, patient, matchedClusters,
                    firstMatchedKey(patient, matched));
        }

        // 阶段二（变更提交）：全部校验通过后才允许写索引。
        boolean created = matchedClusters.isEmpty();
        boolean merged = matchedClusters.size() > 1;
        MasterPatientRecord target = matchedClusters.isEmpty()
                ? createRecord()
                : matchedClusters.get(0);
        if (created) {
            basis.add("no matching identifier; created new MPI");
        }
        for (int i = 1; i < matchedClusters.size(); i++) {
            target = mergeInto(target, matchedClusters.get(i), basis,
                    "shared identifiers in one submission");
        }

        for (PatientIdentifier identifier : patient.getIdentifiers()) {
            String previous = identifierToMpi.putIfAbsent(identifier.canonicalKey(), target.getMpiId());
            if (previous == null) {
                target.addIdentifier(identifier);
                basis.add("linked identifier " + identifier.canonicalKey());
            } else if (previous.equals(target.getMpiId())) {
                basis.add("identifier already known (idempotent) " + identifier.canonicalKey());
            }
        }
        updateDemographics(target, patient, basis);
        return new MergeResult(target.getMpiId(), created, merged, List.copyOf(basis));
    }

    private String firstMatchedKey(NormalizedPatient patient, MasterPatientRecord matched) {
        for (PatientIdentifier identifier : patient.getIdentifiers()) {
            String mpi = identifierToMpi.get(identifier.canonicalKey());
            if (mpi != null && mpi.equals(matched.getMpiId())) {
                return identifier.canonicalKey();
            }
        }
        return null;
    }

    /**
     * 在主索引归并锁内执行动作。复核队列的登记/裁决都通过这里串行化，
     * 保证裁决与新接入交叉时不会出现重复主索引、覆盖或半合并状态。
     * 锁为可重入，动作内部再次调用 upsert 不会死锁。
     */
    public <T> T withMergeLock(java.util.function.Supplier<T> action) {
        mergeLock.lock();
        try {
            return action.get();
        } finally {
            mergeLock.unlock();
        }
    }

    /**
     * 在索引的深拷贝快照上执行一次 upsert，绝不触碰本索引的任何状态。
     * 供接入预检使用：同一条消息重复预检结果一致，且不会改变主索引、
     * 标识归属或已有数据。冲突时抛出与正式接入完全相同的
     * {@link ConflictException}（同样的 reason 与判定依据）。
     */
    public SnapshotOutcome simulate(NormalizedPatient patient) {
        mergeLock.lock();
        try {
            MasterPatientIndex copy = snapshot();
            String targetMpiBefore = firstMatchedMpiId(patient);
            MasterPatientRecord before = targetMpiBefore == null ? null : copy.records.get(targetMpiBefore);
            MergeResult result = copy.upsert(patient);
            MasterPatientRecord after = copy.records.get(result.mpiId());
            return new SnapshotOutcome(result, describeFieldEffects(before, after));
        } finally {
            mergeLock.unlock();
        }
    }

    public record SnapshotOutcome(MergeResult result, List<FieldEffect> fieldEffects) {
    }

    /**
     * 字段级效果：预检/复核记录据此说明哪些人口学字段会保留、哪些会补全，
     * 以及取值来自哪里（existing / incoming / absorbed-cluster）。
     */
    public record FieldEffect(String field, String effect, String source, String after) {
    }

    /**
     * 人工复核“批准合并”的原子操作：
     * <ol>
     *   <li>解析待办涉及的主索引（可能已因别的裁决被并走，按别名追踪）；</li>
     *   <li>若仍是两个独立簇，强制合为一个，幸存者为 MPI id 字典序较小者，
       * 字段按“幸存者已有值优先、空值由被并簇补全”取舍；</li>
     *   <li>以宽松模式（跳过冲突护栏）应用原始提交：挂接新标识、仅补空值；</li>
     *   <li>整个过程在同一把归并锁内完成，与新接入交叉时不会出现
     *       重复主索引、覆盖或半合并状态。</li>
     * </ol>
     */
    public MergeResult applyApprovedMerge(List<String> taskMpiIds, NormalizedPatient patient) {
        mergeLock.lock();
        try {
            List<String> basis = new ArrayList<>();
            MasterPatientRecord survivor = null;
            for (String taskMpiId : taskMpiIds) {
                String currentId = resolveMpi(taskMpiId);
                MasterPatientRecord record = records.get(currentId);
                if (record == null) {
                    continue;
                }
                if (survivor == null) {
                    survivor = record;
                } else if (!record.getMpiId().equals(survivor.getMpiId())) {
                    if (survivor.getMpiId().compareTo(record.getMpiId()) > 0) {
                        survivor = forceMergeInto(record, survivor, basis, "manual review approval");
                    } else {
                        survivor = forceMergeInto(survivor, record, basis, "manual review approval");
                    }
                }
            }
            if (survivor == null) {
                survivor = createRecord();
                basis.add("no reviewed MPI remained; created new MPI");
            } else {
                basis.add("approved by manual review; applying original submission to "
                        + survivor.getMpiId());
            }
            lenientAttachAndFill(survivor, patient, basis);
            return new MergeResult(survivor.getMpiId(), false, true, List.copyOf(basis));
        } finally {
            mergeLock.unlock();
        }
    }

    public MasterPatientRecord findByIdentifier(PatientIdentifier identifier) {
        String mpiId = identifierToMpi.get(identifier.canonicalKey());
        return mpiId == null ? null : records.get(mpiId);
    }

    public MasterPatientRecord findByMpiId(String mpiId) {
        return records.get(resolveMpi(mpiId));
    }

    public int recordCount() {
        return records.size();
    }

    public int identifierCount() {
        return identifierToMpi.size();
    }

    private String resolveMpi(String mpiId) {
        String current = mpiId;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (seen.add(current)) {
            String next = mergedAway.get(current);
            if (next == null) {
                return current;
            }
            current = next;
        }
        return current;
    }

    private MasterPatientIndex snapshot() {
        MasterPatientIndex copy = new MasterPatientIndex();
        copy.sequence.set(this.sequence.get());
        for (Map.Entry<String, String> entry : identifierToMpi.entrySet()) {
            copy.identifierToMpi.put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, MasterPatientRecord> entry : records.entrySet()) {
            copy.records.put(entry.getKey(), entry.getValue().copy());
        }
        for (Map.Entry<String, String> entry : mergedAway.entrySet()) {
            copy.mergedAway.put(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    private String firstMatchedMpiId(NormalizedPatient patient) {
        for (PatientIdentifier identifier : patient.getIdentifiers()) {
            String mpi = identifierToMpi.get(identifier.canonicalKey());
            if (mpi != null) {
                return mpi;
            }
        }
        return null;
    }

    private List<FieldEffect> describeFieldEffects(MasterPatientRecord before,
                                                   MasterPatientRecord after) {
        List<FieldEffect> effects = new ArrayList<>();
        effects.add(fieldEffect("familyName", before == null ? null : before.getFamilyName(),
                after.getFamilyName()));
        effects.add(fieldEffect("givenName", before == null ? null : before.getGivenName(),
                after.getGivenName()));
        effects.add(fieldEffect("birthDate", before == null ? null : before.getBirthDate(),
                after.getBirthDate()));
        effects.add(fieldEffect("gender",
                before == null || before.getGender() == null ? null : before.getGender().name(),
                after.getGender() == null ? null : after.getGender().name()));
        return List.copyOf(effects);
    }

    private static FieldEffect fieldEffect(String field, String before, String after) {
        String effect;
        String source;
        if (after == null) {
            effect = "UNSET";
            source = "none";
        } else if (before == null) {
            effect = "FILLED";
            source = "incoming";
        } else if (before.equals(after)) {
            effect = "KEPT";
            source = "existing";
        } else {
            effect = "FILLED";
            source = "merged-cluster";
        }
        return new FieldEffect(field, effect, source, sanitizeFieldValue(field, after));
    }

    /**
     * 预检输出不允许出现出生日期明文：日期类字段只保留年份；
     * 姓名字段只回“是否有值”，不回值本身。
     */
    private static String sanitizeFieldValue(String field, String value) {
        if (value == null) {
            return null;
        }
        if (field.equals("birthDate")) {
            return value.length() >= 4 ? value.substring(0, 4) : "****";
        }
        if (field.equals("familyName") || field.equals("givenName")) {
            return "<present>";
        }
        return value;
    }

    private MasterPatientRecord forceMergeInto(MasterPatientRecord survivor,
                                               MasterPatientRecord absorbed,
                                               List<String> basis,
                                               String reason) {
        for (String key : absorbed.getIdentifierKeys()) {
            identifierToMpi.put(key, survivor.getMpiId());
        }
        for (PatientIdentifier identifier : absorbed.getIdentifiers()) {
            survivor.addIdentifier(identifier);
        }
        fillField("familyName", survivor.getFamilyName(), absorbed.getFamilyName(),
                survivor::setFamilyName, basis);
        fillField("givenName", survivor.getGivenName(), absorbed.getGivenName(),
                survivor::setGivenName, basis);
        fillField("birthDate", survivor.getBirthDate(), absorbed.getBirthDate(),
                survivor::setBirthDate, basis);
        if (survivor.getGender() == null && absorbed.getGender() != null) {
            survivor.setGender(absorbed.getGender());
            basis.add("gender filled from " + absorbed.getMpiId());
        }
        records.remove(absorbed.getMpiId());
        mergedAway.put(absorbed.getMpiId(), survivor.getMpiId());
        basis.add("force-merged " + absorbed.getMpiId() + " into " + survivor.getMpiId()
                + " (" + reason + ")");
        return survivor;
    }

    private static void fillField(String field, String survivorValue, String absorbedValue,
                                  java.util.function.Consumer<String> setter, List<String> basis) {
        if (survivorValue == null && absorbedValue != null) {
            setter.accept(absorbedValue);
            basis.add(field + " filled from absorbed cluster (survivor value was empty)");
        } else if (survivorValue != null) {
            basis.add(field + " kept from survivor record (existing value wins)");
        }
    }

    private void lenientAttachAndFill(MasterPatientRecord record,
                                      NormalizedPatient patient,
                                      List<String> basis) {
        for (PatientIdentifier identifier : patient.getIdentifiers()) {
            String key = identifier.canonicalKey();
            String previous = identifierToMpi.putIfAbsent(key, record.getMpiId());
            if (previous == null) {
                record.addIdentifier(identifier);
                basis.add("linked identifier " + key);
            } else if (!previous.equals(record.getMpiId())) {
                identifierToMpi.put(key, record.getMpiId());
                record.addIdentifier(identifier);
                basis.add("relinked identifier " + key + " (formerly owned by "
                        + resolveMpi(previous) + ")");
            } else {
                basis.add("identifier already known (idempotent) " + key);
            }
        }
        if (record.getFamilyName() == null && patient.getFamilyName() != null) {
            record.setFamilyName(patient.getFamilyName());
            basis.add("familyName filled from incoming submission");
        }
        if (record.getGivenName() == null && patient.getGivenName() != null) {
            record.setGivenName(patient.getGivenName());
            basis.add("givenName filled from incoming submission");
        }
        if (record.getBirthDate() == null && patient.getBirthDate() != null) {
            record.setBirthDate(patient.getBirthDate());
            basis.add("birthDate filled from incoming submission");
        }
        if (record.getGender() == null && patient.getGender() != null) {
            record.setGender(patient.getGender());
            basis.add("gender filled from incoming submission");
        }
    }

    private MasterPatientRecord createRecord() {
        String mpiId = String.format("MPI-%010d", sequence.incrementAndGet());
        MasterPatientRecord record = new MasterPatientRecord(mpiId);
        records.put(mpiId, record);
        return record;
    }

    /**
     * 两个已有簇被同一条消息链接时的人口学校验。冲突异常携带双方 MPI，
     * 待办因此能如实展示涉及的全部患者。
     */
    private static void assertClustersCompatible(MasterPatientRecord a, MasterPatientRecord b,
                                                 String linkKey) {
        if (a.getBirthDate() != null && b.getBirthDate() != null
                && !a.getBirthDate().equals(b.getBirthDate())) {
            throw new ConflictException(ConflictType.BIRTH_DATE,
                    a.getMpiId(), b.getMpiId(), linkKey,
                    "refusing merge: identifier " + linkKey
                            + " links records with conflicting birth dates; "
                            + "routed to manual review");
        }
        if (a.getFamilyName() != null && b.getFamilyName() != null
                && !a.getFamilyName().equalsIgnoreCase(b.getFamilyName())
                && a.getBirthDate() != null && a.getBirthDate().equals(b.getBirthDate())) {
            throw new ConflictException(ConflictType.FAMILY_NAME,
                a.getMpiId(), b.getMpiId(), linkKey,
                "refusing merge: identifier " + linkKey
                        + " links records with same birth date but conflicting family names; "
                        + "routed to manual review");
        }
    }

    /**
     * 来件人口学与每个命中已有簇的校验。即使消息把多个彼此一致的簇串起来，
     * 来件本身与已有数据冲突时，异常仍携带全部涉及 MPI，保证拒绝零副作用
     * 且待办覆盖双方。
     */
    private static void assertIncomingCompatible(MasterPatientRecord record,
                                                 NormalizedPatient incoming,
                                                 List<MasterPatientRecord> allMatched,
                                                 String matchedKey) {
        List<String> otherMpis = allMatched.stream()
                .map(MasterPatientRecord::getMpiId)
                .filter(id -> !id.equals(record.getMpiId()))
                .toList();
        if (record.getBirthDate() != null && incoming.getBirthDate() != null
                && !record.getBirthDate().equals(incoming.getBirthDate())) {
            throw incomingConflict(ConflictType.BIRTH_DATE, record, otherMpis, matchedKey,
                    "incoming birthDate conflicts");
        }
        if (record.getFamilyName() != null && incoming.getFamilyName() != null
                && !record.getFamilyName().equalsIgnoreCase(incoming.getFamilyName())
                && birthDatesAgree(record, incoming)) {
            throw incomingConflict(ConflictType.FAMILY_NAME, record, otherMpis, matchedKey,
                    "incoming family name conflicts despite same birth date");
        }
    }

    private static boolean birthDatesAgree(MasterPatientRecord record,
                                           NormalizedPatient incoming) {
        return record.getBirthDate() != null && incoming.getBirthDate() != null
                && record.getBirthDate().equals(incoming.getBirthDate());
    }

    private static ConflictException incomingConflict(ConflictType type,
                                                      MasterPatientRecord record,
                                                      List<String> otherMpis,
                                                      String matchedKey,
                                                      String detail) {
        String mpiB = otherMpis.isEmpty() ? null : otherMpis.get(0);
        List<String> extras = otherMpis.size() <= 1
                ? List.of() : otherMpis.subList(1, otherMpis.size());
        return new ConflictException(type, record.getMpiId(), mpiB, matchedKey,
                "refusing update: identifier " + matchedKey + " resolves to "
                        + record.getMpiId() + " but " + detail + "; "
                        + "routed to manual review", extras);
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
        mergedAway.put(absorbed.getMpiId(), survivor.getMpiId());
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
    }
}
