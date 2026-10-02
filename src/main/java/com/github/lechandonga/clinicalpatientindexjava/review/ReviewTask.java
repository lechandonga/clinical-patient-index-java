package com.github.lechandonga.clinicalpatientindexjava.review;

import com.github.lechandonga.clinicalpatientindexjava.domain.ConflictException.ConflictType;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/**
 * 冲突人工复核待办记录。
 *
 * <p>隐私约束：记录中只允许出现 MPI id、替代标识（pseudonym）、标识符哈希，
 * 绝不出现姓名、原始标识符值、出生日期明文。人口学取舍以“规则 + 来源”描述，
 * 不携带具体值。
 */
public class ReviewTask {

    public enum Status {
        OPEN,
        APPROVED,
        REJECTED
    }

    private final String id;
    private final String dedupKey;
    private final List<String> mpiIds;
    private final List<String> pseudonyms;
    private final ConflictType conflictType;
    private final String linkIdentifierHash;
    private final List<String> incomingIdentifierHashes;
    private final String submittedBy;
    private final Instant submittedAt;
    private final List<String> fieldMergePlan;

    private Status status = Status.OPEN;
    private String decidedBy;
    private Instant decidedAt;
    private List<String> decisionBasis = List.of();
    private int submissionCount = 1;

    public ReviewTask(String id,
                      String dedupKey,
                      List<String> mpiIds,
                      List<String> pseudonyms,
                      ConflictType conflictType,
                      String linkIdentifierHash,
                      List<String> incomingIdentifierHashes,
                      String submittedBy,
                      Instant submittedAt,
                      List<String> fieldMergePlan) {
        this.id = id;
        this.dedupKey = dedupKey;
        this.mpiIds = List.copyOf(mpiIds);
        this.pseudonyms = List.copyOf(pseudonyms);
        this.conflictType = conflictType;
        this.linkIdentifierHash = linkIdentifierHash;
        this.incomingIdentifierHashes = List.copyOf(incomingIdentifierHashes);
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.fieldMergePlan = List.copyOf(fieldMergePlan);
    }

    public void recordResubmission(String actor, Instant at) {
        submissionCount++;
    }

    public void decide(Status outcome, String actor, Instant at, List<String> basis) {
        this.status = outcome;
        this.decidedBy = actor;
        this.decidedAt = at;
        this.decisionBasis = List.copyOf(basis);
    }

    public String getId() {
        return id;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getDedupKey() {
        return dedupKey;
    }

    @JsonIgnore
    public List<String> getMpiIds() {
        return mpiIds;
    }

    public List<String> getPseudonyms() {
        return pseudonyms;
    }

    public ConflictType getConflictType() {
        return conflictType;
    }

    public String getLinkIdentifierHash() {
        return linkIdentifierHash;
    }

    public List<String> getIncomingIdentifierHashes() {
        return incomingIdentifierHashes;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public List<String> getFieldMergePlan() {
        return fieldMergePlan;
    }

    public Status getStatus() {
        return status;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public List<String> getDecisionBasis() {
        return decisionBasis;
    }

    public int getSubmissionCount() {
        return submissionCount;
    }
}
