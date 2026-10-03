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
        // 阶段一：纯只读校验。先把本次提交涉及的所有已存在簇解析出来，
        // 在局部投影上检查“簇之间是否兼容”以及“合簇后的人口学是否与来件兼容”。
        // 任何冲突都在触碰 records / identifierToMpi 之前抛出，
        // 因此被拒绝的提交不会产生半合并、标识改挂或字段覆盖。
        List<MasterPatientRecord> matched = resolveMatchedClusters(patient);

        // 阶段二：校验全部通过后才允许落库，此阶段不再可能抛冲突异常。
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
                target = mergeInto(target, existing, basis, key);
            }
        }

        boolean created = false;
        if (target == null) {
            target = createRecord();
            created = true;
            basis.add("no matching identifier; created new MPI");
        }
        boolean merged = matched.size() > 1;

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
    }

    /**
     * 只读解析本次提交命中的全部已存在簇（按标识出现顺序、去重），
     * 同时完成两类冲突校验，任何状态写入都尚未发生：
     * <ol>
     *   <li>被同一消息串起来的簇之间：出生日期冲突，或出生日期相同但姓氏冲突；</li>
     *   <li>投影合簇后的人口学与来件之间：出生日期或姓氏冲突。</li>
     * </ol>
     * 抛出的冲突异常携带全部涉及的主索引，保证待办能如实列出双方
     * （或多方），去重键也覆盖完整患者集合。
     */
    private List<MasterPatientRecord> resolveMatchedClusters(NormalizedPatient patient) {
        List<MasterPatientRecord> matched = new ArrayList<>();
        ProjectedDemographics projected = null;
        String firstMatchedIncomingKey = null;
        for (PatientIdentifier identifier : patient.getIdentifiers()) {
            String key = identifier.canonicalKey();
            String existingMpi = identifierToMpi.get(key);
            if (existingMpi == null) {
                continue;
            }
            MasterPatientRecord existing = records.get(existingMpi);
            if (matched.stream().anyMatch(r -> r.getMpiId().equals(existing.getMpiId()))) {
                continue;
            }
            if (projected == null) {
                firstMatchedIncomingKey = key;
                matched.add(existing);
                projected = ProjectedDemographics.of(existing);
            } else {
                assertClustersCompatible(matched.get(0), existing, matched, projected, key);
                projected.absorb(existing);
                matched.add(existing);
            }
        }
        if (projected != null) {
            assertIncomingCompatible(matched, projected, patient, firstMatchedIncomingKey);
        }
        return matched;
    }

    /** 合簇过程中的人口学投影：已有簇非空字段保留，空值由后续簇补全。 */
    private static final class ProjectedDemographics {
        private String familyName;
        private String birthDate;

        static ProjectedDemographics of(MasterPatientRecord record) {
            ProjectedDemographics p = new ProjectedDemographics();
            p.familyName = record.getFamilyName();
            p.birthDate = record.getBirthDate();
            return p;
        }

        void absorb(MasterPatientRecord record) {
            if (familyName == null) {
                familyName = record.getFamilyName();
            }
            if (birthDate == null) {
                birthDate = record.getBirthDate();
            }
        }
    }

    private static void assertClustersCompatible(MasterPatientRecord first,
                                                 MasterPatientRecord candidate,
                                                 List<MasterPatientRecord> priorMatched,
                                                 ProjectedDemographics projected,
                                                 String linkKey) {
        if (projected.birthDate != null && candidate.getBirthDate() != null
                && !projected.birthDate.equals(candidate.getBirthDate())) {
            throw new ConflictException(ConflictType.BIRTH_DATE,
                    first.getMpiId(), candidate.getMpiId(), linkKey,
                    "refusing merge: identifier " + linkKey
                            + " links records with conflicting birth dates; "
                            + "routed to manual review",
                    otherMpiIds(first, candidate, priorMatched));
        }
        if (projected.familyName != null && candidate.getFamilyName() != null
                && !projected.familyName.equalsIgnoreCase(candidate.getFamilyName())
                && projected.birthDate != null
                && projected.birthDate.equals(candidate.getBirthDate())) {
            throw new ConflictException(ConflictType.FAMILY_NAME,
                    first.getMpiId(), candidate.getMpiId(), linkKey,
                    "refusing merge: identifier " + linkKey
                            + " links records with same birth date but conflicting family names; "
                            + "routed to manual review",
                    otherMpiIds(first, candidate, priorMatched));
        }
    }

    private static void assertIncomingCompatible(List<MasterPatientRecord> matched,
                                                 ProjectedDemographics projected,
                                                 NormalizedPatient incoming,
                                                 String matchedKey) {
        MasterPatientRecord first = matched.get(0);
        if (projected.birthDate != null && incoming.getBirthDate() != null
                && !projected.birthDate.equals(incoming.getBirthDate())) {
            throw new ConflictException(ConflictType.BIRTH_DATE,
                    first.getMpiId(), null, matchedKey,
                    "refusing update: identifiers resolve to " + matchedMpiIds(matched)
                            + " but incoming birthDate conflicts with existing records; "
                            + "routed to manual review",
                    otherMpiIds(first, null, matched));
        }
        if (projected.familyName != null && incoming.getFamilyName() != null
                && !projected.familyName.equalsIgnoreCase(incoming.getFamilyName())) {
            throw new ConflictException(ConflictType.FAMILY_NAME,
                    first.getMpiId(), null, matchedKey,
                    "refusing update: identifiers resolve to " + matchedMpiIds(matched)
                            + " but incoming family name conflicts with existing records; "
                            + "routed to manual review",
                    otherMpiIds(first, null, matched));
        }
    }

    private static List<String> matchedMpiIds(List<MasterPatientRecord> matched) {
        return matched.stream().map(MasterPatientRecord::getMpiId).sorted().distinct().toList();
    }

    /** 除显式 a/b 两方外，本次命中的其余主索引（多簇场景下保证待办不遗漏任何一方）。 */
    private static List<String> otherMpiIds(MasterPatientRecord a,
                                            MasterPatientRecord b,
                                            List<MasterPatientRecord> matched) {
        List<String> others = new ArrayList<>();
        for (MasterPatientRecord record : matched) {
            String id = record.getMpiId();
            if (!id.equals(a.getMpiId()) && (b == null || !id.equals(b.getMpiId()))) {
                others.add(id);
            }
        }
        return List.copyOf(others);
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
        if (record.getBirthDate() != null && patient.getBirthDate() != null
                && !record.getBirthDate().equals(patient.getBirthDate())) {
            basis.add("kept existing birthDate " + record.getBirthDate()
                    + " (incoming " + patient.getBirthDate() + " ignored as conflicting)");
        }
    }
}
