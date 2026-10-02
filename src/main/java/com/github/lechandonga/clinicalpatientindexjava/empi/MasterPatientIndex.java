package com.github.lechandonga.clinicalpatientindexjava.empi;

import com.github.lechandonga.clinicalpatientindexjava.domain.ConflictDetectedException;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException.RejectionReason;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 并发安全的本地患者主索引。
 *
 * 采用「先规划、后提交」结构：{@link #planUpsert} 是纯只读判定，产出
 * {@link UpsertPlan}（或抛出与正式接入完全相同的异常），不触碰任何索引状态；
 * {@link #applyPlan} 在同一把归并锁内按规划一次性原子落地。预检接口直接复用
 * {@code planUpsert}，因此同一条消息重复预检结果一致、预检前后状态完全相同，
 * 即使正式提交会被拒绝或触发冲突，预检也没有任何副作用。
 *
 * 归并规则：
 * <ul>
 *   <li>标识符 canonical key 相同（忽略大小写、空白、常见前缀）视为同一人；</li>
 *   <li>一次提交携带多个标识符时，这些标识符互相链接（union-find）；</li>
 *   <li>两个已存在簇通过本次提交链接时，若出生日期冲突（或生日相同但姓氏冲突），
 *       抛出 {@link ConflictDetectedException}，不做任何部分修改；</li>
 *   <li>命中单个已有簇但来件人口学与该簇冲突时同样拒绝，不做任何部分修改。</li>
 * </ul>
 * 单把可重入归并锁保证：重复主索引不会产生、更新不会丢失、不存在半合并状态。
 */
public class MasterPatientIndex {

    private final ReentrantLock mergeLock = new ReentrantLock();
    private final Map<String, MasterPatientRecord> records = new ConcurrentHashMap<>();
    private final Map<String, String> identifierToMpi = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public record MergeResult(String mpiId, boolean created, boolean merged, List<String> basis) {
    }

    /**
     * 字段级取舍说明：解释合并/更新后该字段取哪个值以及为什么。
     */
    public record FieldDecision(String field, String effect, String chosenValue,
                                String basis, String source) {
    }

    /**
     * 单个标识符的归属动作。
     */
    public record IdentifierDecision(String system, String canonicalKey, String effect) {
    }

    /**
     * 只读规划结果：描述“这条消息按现有规则会发生什么”，不含任何待写入动作的执行。
     *
     * @param createNew           是否会新建主索引
     * @param survivorMpiId       命中的目标 MPI ID（createNew 时为 null）
     * @param absorbedMpiIds      会被合并消失的其他簇 MPI ID
     * @param identifierDecisions 每个标识符的归属结果
     * @param fieldDecisions      每个人口学字段的保留/补全/忽略依据
     * @param basis               判定依据文本
     */
    public record UpsertPlan(boolean createNew,
                             String survivorMpiId,
                             List<String> absorbedMpiIds,
                             List<IdentifierDecision> identifierDecisions,
                             List<FieldDecision> fieldDecisions,
                             List<String> basis) {

        public boolean merged() {
            return !absorbedMpiIds.isEmpty();
        }
    }

    public MergeResult upsert(NormalizedPatient patient) {
        mergeLock.lock();
        try {
            UpsertPlan plan = planUpsertLocked(patient);
            applyPlanLocked(plan, patient);
            String mpiId = plan.createNew()
                    ? patient.getIdentifiers().stream().findFirst()
                            .map(id -> identifierToMpi.get(id.canonicalKey())).orElseThrow()
                    : plan.survivorMpiId();
            return new MergeResult(mpiId, plan.createNew(), plan.merged(), plan.basis());
        } finally {
            mergeLock.unlock();
        }
    }

    /**
     * 纯只读：按当前索引状态推演该消息的处理结果，绝不修改任何数据。
     */
    public UpsertPlan planUpsert(NormalizedPatient patient) {
        mergeLock.lock();
        try {
            return planUpsertLocked(patient);
        } finally {
            mergeLock.unlock();
        }
    }

    /**
     * 在归并锁内落地一个此前推演的规划。常规接入在同一把锁内“规划+提交”。
     */
    public void applyPlan(UpsertPlan plan, NormalizedPatient patient) {
        mergeLock.lock();
        try {
            applyPlanLocked(plan, patient);
        } finally {
            mergeLock.unlock();
        }
    }

    private UpsertPlan planUpsertLocked(NormalizedPatient patient) {
        List<String> basis = new ArrayList<>();
        MasterPatientRecord survivor = null;
        Set<MasterPatientRecord> matchedInOrder = new LinkedHashSet<>();

        for (PatientIdentifier identifier : patient.getIdentifiers()) {
            String key = identifier.canonicalKey();
            String existingMpi = identifierToMpi.get(key);
            if (existingMpi == null) {
                continue;
            }
            MasterPatientRecord existing = records.get(existingMpi);
            matchedInOrder.add(existing);
            if (survivor == null) {
                survivor = existing;
                basis.add("matched identifier " + key);
            } else if (!existing.getMpiId().equals(survivor.getMpiId())) {
                String category = conflictCategory(survivor, existing);
                if (category != null) {
                    throw new ConflictDetectedException(survivor.getMpiId(), existing.getMpiId(),
                            category,
                            "refusing merge: shared identifier links records with conflicting "
                                    + category + " (" + survivor.getMpiId() + " vs "
                                    + existing.getMpiId() + ")");
                }
                basis.add("would merge " + existing.getMpiId() + " into " + survivor.getMpiId()
                        + " via shared identifier " + key);
            }
        }

        boolean createNew = survivor == null;
        List<String> absorbedMpiIds = new ArrayList<>();
        if (!createNew) {
            for (MasterPatientRecord other : matchedInOrder) {
                if (!other.getMpiId().equals(survivor.getMpiId())) {
                    absorbedMpiIds.add(other.getMpiId());
                    survivor = combineInMemory(survivor, other);
                }
            }
            assertIncomingCompatible(survivor, patient);
        }

        List<IdentifierDecision> identifierDecisions = new ArrayList<>();
        for (PatientIdentifier identifier : patient.getIdentifiers()) {
            String key = identifier.canonicalKey();
            String existingMpi = identifierToMpi.get(key);
            String effect;
            if (existingMpi == null) {
                effect = createNew ? "ATTACH_NEW" : "LINK_TO_EXISTING";
                basis.add("linked identifier " + key);
            } else if (survivor != null && existingMpi.equals(survivor.getMpiId())) {
                effect = "ALREADY_KNOWN";
                basis.add("identifier already known (idempotent) " + key);
            } else {
                effect = "RELINKED_FROM_ABSORBED";
            }
            identifierDecisions.add(new IdentifierDecision(
                    PatientIdentifier.normalizeSystem(identifier.system()), key, effect));
        }
        if (createNew) {
            basis.add("no matching identifier; created new MPI");
        }

        List<FieldDecision> fieldDecisions = new ArrayList<>();
        addFieldDecision(fieldDecisions, "familyName",
                survivor == null ? null : survivor.getFamilyName(), patient.getFamilyName());
        addFieldDecision(fieldDecisions, "givenName",
                survivor == null ? null : survivor.getGivenName(), patient.getGivenName());
        addFieldDecision(fieldDecisions, "birthDate",
                survivor == null ? null : survivor.getBirthDate(), patient.getBirthDate());
        String existingGender = survivor == null || survivor.getGender() == null
                ? null : survivor.getGender().name();
        String incomingGender = patient.getGender() == null ? null : patient.getGender().name();
        addFieldDecision(fieldDecisions, "gender", existingGender, incomingGender);

        return new UpsertPlan(createNew,
                createNew ? null : survivor.getMpiId(),
                List.copyOf(absorbedMpiIds),
                List.copyOf(identifierDecisions),
                List.copyOf(fieldDecisions),
                List.copyOf(basis));
    }

    private void applyPlanLocked(UpsertPlan plan, NormalizedPatient patient) {
        MasterPatientRecord survivor;
        if (plan.createNew()) {
            survivor = createRecord();
        } else {
            survivor = records.get(plan.survivorMpiId());
            if (survivor == null) {
                throw new IllegalStateException("planned MPI no longer exists: " + plan.survivorMpiId());
            }
            for (String absorbedId : plan.absorbedMpiIds()) {
                MasterPatientRecord absorbed = records.get(absorbedId);
                if (absorbed != null) {
                    absorb(survivor, absorbed);
                }
            }
        }

        for (PatientIdentifier identifier : patient.getIdentifiers()) {
            String key = identifier.canonicalKey();
            String previous = identifierToMpi.putIfAbsent(key, survivor.getMpiId());
            if (previous == null || previous.equals(survivor.getMpiId())) {
                survivor.addIdentifier(identifier);
            }
        }
        if (survivor.getFamilyName() == null) {
            survivor.setFamilyName(patient.getFamilyName());
        }
        if (survivor.getGivenName() == null) {
            survivor.setGivenName(patient.getGivenName());
        }
        if (survivor.getBirthDate() == null) {
            survivor.setBirthDate(patient.getBirthDate());
        }
        if (survivor.getGender() == null) {
            survivor.setGender(patient.getGender());
        }
    }

    /**
     * 人工复核批准后的强制合并：跳过人口学护栏，按确定的字段取舍规则
     * （survivor 已有值优先、absorbed 非空值补全）一次性原子完成。
     * 返回每个字段的取舍依据，供裁决记录可解释留存。
     */
    public List<FieldDecision> forceMerge(String survivorMpiId, String absorbedMpiId) {
        mergeLock.lock();
        try {
            MasterPatientRecord survivor = records.get(survivorMpiId);
            MasterPatientRecord absorbed = records.get(absorbedMpiId);
            if (survivor == null || absorbed == null) {
                throw new IllegalStateException(
                        "cannot merge: " + survivorMpiId + " or " + absorbedMpiId + " missing");
            }
            List<FieldDecision> decisions = new ArrayList<>();
            addFieldDecision(decisions, "familyName",
                    survivor.getFamilyName(), absorbed.getFamilyName());
            addFieldDecision(decisions, "givenName",
                    survivor.getGivenName(), absorbed.getGivenName());
            addFieldDecision(decisions, "birthDate",
                    survivor.getBirthDate(), absorbed.getBirthDate());
            addFieldDecision(decisions, "gender",
                    survivor.getGender() == null ? null : survivor.getGender().name(),
                    absorbed.getGender() == null ? null : absorbed.getGender().name());
            absorb(survivor, absorbed);
            return List.copyOf(decisions);
        } finally {
            mergeLock.unlock();
        }
    }

    private void absorb(MasterPatientRecord survivor, MasterPatientRecord absorbed) {
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
    }

    /**
     * 纯内存叠合（不落库），用于规划阶段计算合并后的字段取舍。
     */
    private MasterPatientRecord combineInMemory(MasterPatientRecord base, MasterPatientRecord other) {
        MasterPatientRecord combined = new MasterPatientRecord(base.getMpiId());
        base.getIdentifiers().forEach(combined::addIdentifier);
        other.getIdentifiers().forEach(combined::addIdentifier);
        combined.setFamilyName(base.getFamilyName() != null ? base.getFamilyName() : other.getFamilyName());
        combined.setGivenName(base.getGivenName() != null ? base.getGivenName() : other.getGivenName());
        combined.setBirthDate(base.getBirthDate() != null ? base.getBirthDate() : other.getBirthDate());
        combined.setGender(base.getGender() != null ? base.getGender() : other.getGender());
        return combined;
    }

    private static String conflictCategory(MasterPatientRecord a, MasterPatientRecord b) {
        if (a.getBirthDate() != null && b.getBirthDate() != null
                && !a.getBirthDate().equals(b.getBirthDate())) {
            return "BIRTH_DATE";
        }
        if (a.getFamilyName() != null && b.getFamilyName() != null
                && !a.getFamilyName().equalsIgnoreCase(b.getFamilyName())
                && a.getBirthDate() != null && a.getBirthDate().equals(b.getBirthDate())) {
            return "FAMILY_NAME";
        }
        return null;
    }

    private static void assertIncomingCompatible(MasterPatientRecord record, NormalizedPatient incoming) {
        if (record.getBirthDate() != null && incoming.getBirthDate() != null
                && !record.getBirthDate().equals(incoming.getBirthDate())) {
            throw new IngestionException(RejectionReason.CONTRADICTORY_DATA,
                    "refusing update: identifier resolves to " + record.getMpiId()
                            + " whose birthDate conflicts with the incoming birthDate");
        }
        if (record.getFamilyName() != null && incoming.getFamilyName() != null
                && !record.getFamilyName().equalsIgnoreCase(incoming.getFamilyName())) {
            throw new IngestionException(RejectionReason.CONTRADICTORY_DATA,
                    "refusing update: identifier resolves to " + record.getMpiId()
                            + " whose family name conflicts with the incoming family name");
        }
    }

    private static void addFieldDecision(List<FieldDecision> decisions, String field,
                                         String existingValue, String incomingValue) {
        String effect;
        String chosen;
        String basis;
        if (existingValue != null) {
            chosen = existingValue;
            if (incomingValue == null) {
                effect = "KEEP_EXISTING";
                basis = "existing value retained; incoming message has no value for " + field;
            } else if (existingValue.equalsIgnoreCase(incomingValue)) {
                effect = "KEEP_EXISTING";
                basis = "existing and incoming values agree; existing value retained";
            } else {
                effect = "KEEP_EXISTING";
                basis = "existing value takes precedence; conflicting incoming value ignored for " + field;
            }
        } else if (incomingValue != null) {
            effect = "FILL_FROM_INCOMING";
            chosen = incomingValue;
            basis = "existing record has no value; completed from incoming message";
        } else {
            effect = "UNCHANGED_EMPTY";
            chosen = null;
            basis = "no value on either side; left empty";
        }
        decisions.add(new FieldDecision(field, effect, chosen, basis,
                chosen == null ? null : (existingValue != null ? "EXISTING_MPI" : "INCOMING_MESSAGE")));
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

    /**
     * 仅供本地测试隔离使用：清空全部索引状态。
     */
    public void resetForTesting() {
        mergeLock.lock();
        try {
            records.clear();
            identifierToMpi.clear();
            sequence.set(0);
        } finally {
            mergeLock.unlock();
        }
    }

    private MasterPatientRecord createRecord() {
        String mpiId = String.format("MPI-%010d", sequence.incrementAndGet());
        MasterPatientRecord record = new MasterPatientRecord(mpiId);
        records.put(mpiId, record);
        return record;
    }
}
