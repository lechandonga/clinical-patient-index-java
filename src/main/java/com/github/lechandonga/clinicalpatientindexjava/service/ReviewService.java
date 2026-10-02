package com.github.lechandonga.clinicalpatientindexjava.service;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditLog;
import com.github.lechandonga.clinicalpatientindexjava.domain.ConflictException;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex.MergeResult;
import com.github.lechandonga.clinicalpatientindexjava.privacy.Pseudonymizer;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewQueue;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewStagingStore;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final MasterPatientIndex index;
    private final ReviewQueue queue;
    private final ReviewStagingStore stagingStore;
    private final Pseudonymizer pseudonymizer;
    private final AuditLog auditLog;

    public ReviewService(MasterPatientIndex index,
                         ReviewQueue queue,
                         ReviewStagingStore stagingStore,
                         Pseudonymizer pseudonymizer,
                         AuditLog auditLog) {
        this.index = index;
        this.queue = queue;
        this.stagingStore = stagingStore;
        this.pseudonymizer = pseudonymizer;
        this.auditLog = auditLog;
    }

    // ============================ 预检 ============================

    /**
     * 只读预检：在索引深拷贝快照上模拟正式接入，绝不修改主索引、标识归属
     * 与已有数据。拒绝原因与正式接入完全同源；队列满也按当前真实状态模拟，
     * 给出可区分的 REVIEW_QUEUE_FULL。
     */
    public PreviewResult preview(NormalizedPatient patient, String formatVersion, String actor) {
        return index.withMergeLock(() -> {
            MasterPatientIndex.SnapshotOutcome outcome;
            try {
                outcome = index.simulate(patient);
            } catch (ConflictException conflict) {
                throw previewRejection(conflict, patient, actor, formatVersion);
            }
            MergeResult result = outcome.result();
            String pseudonym = pseudonymizer.pseudonym(result.mpiId());
            List<String> safeBasis = hashBasisList(result.basis());
            List<FieldEffectView> effects = outcome.fieldEffects().stream()
                    .map(e -> new FieldEffectView(e.field(), e.effect(), e.source(), e.after()))
                    .toList();
            log.info("[PREVIEW OK] actor={} format={} wouldCreate={} merged={} inputSummary={} effects={}",
                    actor, formatVersion, result.created(), result.merged(),
                    patient.summary(),
                    effects.stream().map(e -> e.field() + "=" + e.effect()).toList());
            auditLog.record(actor, "PREVIEW", result.mpiId(), pseudonym,
                    firstIdentifierHash(patient),
                    result.created() ? "WOULD_CREATE" : "WOULD_MERGE",
                    "read-only preview; basis=" + safeBasis);
            return new PreviewResult(true, null, null, result.created(), pseudonym,
                    NormalizedView.of(patient, pseudonymizer), effects, safeBasis,
                    patient.getNotes());
        });
    }

    private IngestionException previewRejection(ConflictException conflict,
                                                NormalizedPatient patient,
                                                String actor,
                                                String formatVersion) {
        ReviewTask dup = queue.findOpenByDedupKey(conflict.dedupKey());
        IngestionException rejection;
        if (dup == null && queue.openCount() >= queue.getLimit()) {
            rejection = new IngestionException(IngestionException.RejectionReason.REVIEW_QUEUE_FULL,
                    "conflict detected but manual review queue is full (limit "
                            + queue.getLimit() + "); formal submission would be rejected "
                            + "without creating a review task");
        } else {
            rejection = conflict;
        }
        log.info("[PREVIEW REJECT] actor={} format={} reason={} inputSummary={}",
                actor, formatVersion, rejection.getReason(), patient.summary());
        auditLog.record(actor, "PREVIEW", null, null, firstIdentifierHash(patient),
                "REJECTED:" + rejection.getReason(),
                "read-only preview; "
                        + IngestionSupport.hashIdentifierValues(rejection.getMessage(),
                        pseudonymizer));
        return rejection;
    }

    // ===================== 冲突登记（正式接入时） =====================

    /**
     * 正式接入冲突时在归并锁内调用：登记/复用待办；队列满则抛
     * REVIEW_QUEUE_FULL。冲突检测发生在任何索引写入之前，调用方捕获后
     * 才到这里，因此无论登记成功与否，索引、标识归属与人口学数据都零变化。
     */
    public void registerConflict(ConflictException conflict, NormalizedPatient patient, String actor) {
        index.withMergeLock(() -> {
            ReviewTask existing = queue.findOpenByDedupKey(conflict.dedupKey());
            if (existing != null) {
                existing.recordResubmission(actor, Instant.now());
                log.info("[REVIEW DEDUP] task={} type={} actor={} submissionCount={}",
                        existing.getId(), existing.getConflictType(), actor,
                        existing.getSubmissionCount());
                auditLog.record(actor, "INGEST_CONFLICT", null, null,
                        hashKey(conflict.getLinkIdentifierKey()),
                        "REJECTED:CONTRADICTORY_DATA",
                        "deduplicated into existing open review " + existing.getId()
                                + "; submissionCount=" + existing.getSubmissionCount());
                return null;
            }
            if (queue.openCount() >= queue.getLimit()) {
                auditLog.record(actor, "INGEST_CONFLICT", null, null,
                        hashKey(conflict.getLinkIdentifierKey()), "REJECTED:REVIEW_QUEUE_FULL",
                        "review queue full (limit " + queue.getLimit()
                                + "); conflict rejected, no task created");
                throw new IngestionException(IngestionException.RejectionReason.REVIEW_QUEUE_FULL,
                        "conflict detected but manual review queue is full (limit "
                                + queue.getLimit() + "); submission rejected, no review task created");
            }
            ReviewTask task = buildTask(conflict, patient, actor, Instant.now());
            ReviewTask stored = queue.add(task, stagingStore, patient);
            if (stored == null) {
                throw new IngestionException(IngestionException.RejectionReason.REVIEW_QUEUE_FULL,
                        "conflict detected but manual review queue is full (limit "
                                + queue.getLimit() + ")");
            }
            log.info("[REVIEW OPEN] id={} type={} mpiCount={} actor={}",
                    task.getId(), task.getConflictType(), task.getMpiIds().size(), actor);
            auditLog.record(actor, "INGEST_CONFLICT", null, null,
                    hashKey(conflict.getLinkIdentifierKey()), "REJECTED:CONTRADICTORY_DATA",
                    "review task " + task.getId() + " opened; type=" + task.getConflictType()
                            + "; involvedPseudonyms=" + task.getPseudonyms());
            return null;
        });
    }

    private ReviewTask buildTask(ConflictException conflict,
                                 NormalizedPatient patient,
                                 String actor,
                                 Instant now) {
        List<String> mpiIds = conflict.getMpiIds();
        List<String> pseudonyms = mpiIds.stream().map(pseudonymizer::pseudonym).toList();
        List<String> incomingHashes = patient.getIdentifiers().stream()
                .map(PatientIdentifier::canonicalKey).map(this::hashKey).toList();
        return new ReviewTask(queue.nextId(), conflict.dedupKey(), mpiIds, pseudonyms,
                conflict.getConflictType(), hashKey(conflict.getLinkIdentifierKey()),
                incomingHashes, actor, now, mergePlan(conflict, mpiIds));
    }

    /**
     * 字段取舍规则（确定且可解释，记录在待办中，不含任何明文值）：
     * 双簇合并时 MPI id 字典序较小者为幸存者，其非空字段一律保留，
     * 空字段由被并簇补全，之后原提交只补仍为空的字段；单簇冲突批准时，
     * 现有记录字段保留，原提交只补空值。冲突字段永远以幸存者/现有值为准。
     */
    private List<String> mergePlan(ConflictException conflict, List<String> mpiIds) {
        List<String> plan = new ArrayList<>();
        if (mpiIds.size() >= 2) {
            String survivor = mpiIds.get(0);
            plan.add("on approval: merge " + mpiIds.subList(1, mpiIds.size()) + " into " + survivor
                    + " (lower MPI id is the deterministic survivor)");
            for (String field : List.of("familyName", "givenName", "birthDate", "gender")) {
                plan.add(field + ": keep survivor non-null value; fill from absorbed clusters "
                        + "(deterministic MPI order) only when survivor value is empty; the "
                        + "original incoming submission may fill only still-empty fields");
            }
        } else {
            plan.add("on approval: keep existing MPI " + mpiIds.get(0)
                    + " demographics; incoming submission fills only empty fields");
        }
        plan.add("conflicting field " + conflict.getConflictType()
                + " is resolved in favor of the survivor/existing value per this plan");
        return List.copyOf(plan);
    }

    // ===================== 待办查询与裁决 =====================

    public List<ReviewTask> listOpen(String actor) {
        List<ReviewTask> open = index.withMergeLock(queue::findOpen);
        auditLog.record(actor, "REVIEW_LIST", null, null, null,
                "OK", "openReviewCount=" + open.size());
        return open;
    }

    public DecisionResult decide(String reviewId, boolean approve, String actor) {
        return index.withMergeLock(() -> {
            ReviewTask task = queue.findById(reviewId);
            if (task == null) {
                throw new IngestionException(IngestionException.RejectionReason.REVIEW_TASK_NOT_FOUND,
                        "review task " + reviewId + " does not exist");
            }
            ReviewTask.Status intended = approve
                    ? ReviewTask.Status.APPROVED : ReviewTask.Status.REJECTED;
            if (task.getStatus() != ReviewTask.Status.OPEN) {
                boolean same = task.getStatus() == intended;
                if (!same) {
                    auditLog.record(actor, "REVIEW_DECIDE", null, null, null,
                            "REJECTED:REVIEW_ALREADY_DECIDED",
                            "task=" + reviewId + " already decided " + task.getStatus()
                                    + " by " + task.getDecidedBy());
                    throw new IngestionException(
                            IngestionException.RejectionReason.REVIEW_ALREADY_DECIDED,
                            "review task " + reviewId + " is already decided as "
                                    + task.getStatus() + " by " + task.getDecidedBy()
                                    + "; opposite decisions are not allowed");
                }
                auditLog.record(actor, "REVIEW_DECIDE", null, null, null,
                        "IDEMPOTENT:" + task.getStatus(),
                        "task=" + reviewId + " already decided " + task.getStatus()
                                + " by " + task.getDecidedBy());
                return new DecisionResult(reviewId, task.getStatus().name(), false,
                        task.getDecidedBy(), task.getDecidedAt(), task.getDecisionBasis());
            }
            // 原子“认领”待办：并发裁决只有一个线程拿到非 null，其余拿到同样结果。
            ReviewTask settled = queue.settle(task);
            if (settled == null) {
                auditLog.record(actor, "REVIEW_DECIDE", null, null, null,
                        "IDEMPOTENT:" + task.getStatus(),
                        "task=" + reviewId + " settled concurrently by another caller");
                return new DecisionResult(reviewId, task.getStatus().name(), false,
                        task.getDecidedBy(), task.getDecidedAt(), task.getDecisionBasis());
            }
            Instant now = Instant.now();
            if (approve) {
                NormalizedPatient payload = stagingStore.take(reviewId);
                if (payload == null) {
                    settled.decide(ReviewTask.Status.REJECTED, actor, now,
                            List.of("staged payload unavailable; rejected to protect data integrity"));
                    auditLog.record(actor, "REVIEW_DECIDE", null, null,
                            task.getLinkIdentifierHash(), "REJECTED:STAGING_MISSING",
                            "task=" + reviewId + "; staged payload unavailable");
                    return new DecisionResult(reviewId, "REJECTED", true, actor, now,
                            List.of("staged payload unavailable; rejected to protect data integrity"));
                }
                MergeResult merged = index.applyApprovedMerge(task.getMpiIds(), payload);
                List<String> basis = new ArrayList<>(task.getFieldMergePlan());
                basis.addAll(merged.basis());
                List<String> safeBasis = hashBasisList(basis);
                settled.decide(ReviewTask.Status.APPROVED, actor, now, safeBasis);
                auditLog.record(actor, "REVIEW_DECIDE", merged.mpiId(),
                        pseudonymizer.pseudonym(merged.mpiId()), task.getLinkIdentifierHash(),
                        "APPROVED", "task=" + reviewId + "; basis=" + safeBasis);
                log.info("[REVIEW APPROVE] task={} survivor={} actor={}",
                        reviewId, merged.mpiId(), actor);
                return new DecisionResult(reviewId, "APPROVED", true, actor, now, safeBasis);
            }
            stagingStore.take(reviewId);
            List<String> basis = List.of("rejected by reviewer; index and identifier ownership "
                    + "remain untouched");
            settled.decide(ReviewTask.Status.REJECTED, actor, now, basis);
            auditLog.record(actor, "REVIEW_DECIDE", null, null, task.getLinkIdentifierHash(),
                    "REJECTED", "task=" + reviewId + "; no data changed");
            log.info("[REVIEW REJECT] task={} actor={} (no data changed)", reviewId, actor);
            return new DecisionResult(reviewId, "REJECTED", true, actor, now, basis);
        });
    }

    // ===================== 工具与 DTO =====================

    private List<String> hashBasisList(List<String> entries) {
        return entries.stream()
                .map(e -> IngestionSupport.hashIdentifierValues(e, pseudonymizer))
                .toList();
    }

    private String hashKey(String canonicalKey) {
        return canonicalKey == null ? null : pseudonymizer.hashIdentifier(canonicalKey);
    }

    private String firstIdentifierHash(NormalizedPatient patient) {
        return patient.getIdentifiers().stream().findFirst()
                .map(PatientIdentifier::canonicalKey).map(pseudonymizer::hashIdentifier)
                .orElse(null);
    }

    public record PreviewResult(boolean acceptable, String reason, String detail,
                                boolean wouldCreate, String targetPseudonym,
                                NormalizedView normalized,
                                List<FieldEffectView> fieldEffects,
                                List<String> mergeBasis,
                                List<String> normalizationNotes) {
    }

    /**
     * 归一结果视图：出生日期只降维到出生年；不含姓名、原始标识符值。
     */
    public record NormalizedView(String gender, Integer birthYear,
                                 int identifierCount, int sourceSystemCount,
                                 List<String> identifierHashes,
                                 List<String> notes) {
        static NormalizedView of(NormalizedPatient patient, Pseudonymizer pseudonymizer) {
            Integer birthYear = patient.getBirthDate() == null
                    ? null : Integer.parseInt(patient.getBirthDate().substring(0, 4));
            int sourceCount = (int) patient.getIdentifiers().stream()
                    .map(PatientIdentifier::system).distinct().count();
            List<String> hashes = patient.getIdentifiers().stream()
                    .map(PatientIdentifier::canonicalKey)
                    .map(pseudonymizer::hashIdentifier)
                    .toList();
            return new NormalizedView(
                    patient.getGender() == null ? "UNKNOWN" : patient.getGender().name(),
                    birthYear, patient.getIdentifiers().size(), sourceCount,
                    hashes, patient.getNotes());
        }
    }

    public record FieldEffectView(String field, String effect, String source, String after) {
    }

    public record DecisionResult(String reviewId, String decision, boolean applied,
                                 String decidedBy, Instant decidedAt,
                                 List<String> basis) {
    }
}
