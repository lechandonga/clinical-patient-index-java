package com.github.lechandonga.clinicalpatientindexjava.review;

import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex.FieldDecision;

import java.time.Instant;
import java.util.List;

/**
 * 冲突人工复核待办记录。
 *
 * 只包含替代标识、冲突类别、提交者与时间等非明文信息，绝不包含姓名、
 * 原始标识符值、出生日期等明文敏感字段。
 */
public class ReviewTicket {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    private final String ticketId;
    private final String pseudonymA;
    private final String pseudonymB;
    private final String mpiIdA;
    private final String mpiIdB;
    private final String conflictCategory;
    private final String submittedBy;
    private final Instant submittedAt;
    private volatile Status status = Status.PENDING;
    private volatile String decidedBy;
    private volatile Instant decidedAt;
    private volatile List<FieldDecision> mergeDecisions;
    private volatile String dedupNote;

    public ReviewTicket(String ticketId,
                        String mpiIdA,
                        String mpiIdB,
                        String pseudonymA,
                        String pseudonymB,
                        String conflictCategory,
                        String submittedBy,
                        Instant submittedAt) {
        this.ticketId = ticketId;
        this.mpiIdA = mpiIdA;
        this.mpiIdB = mpiIdB;
        this.pseudonymA = pseudonymA;
        this.pseudonymB = pseudonymB;
        this.conflictCategory = conflictCategory;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getPseudonymA() {
        return pseudonymA;
    }

    public String getPseudonymB() {
        return pseudonymB;
    }

    public String getMpiIdA() {
        return mpiIdA;
    }

    public String getMpiIdB() {
        return mpiIdB;
    }

    public String getConflictCategory() {
        return conflictCategory;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
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

    public List<FieldDecision> getMergeDecisions() {
        return mergeDecisions;
    }

    public String getDedupNote() {
        return dedupNote;
    }

    void markApproved(String reviewer, Instant at, List<FieldDecision> decisions, String note) {
        this.status = Status.APPROVED;
        this.decidedBy = reviewer;
        this.decidedAt = at;
        this.mergeDecisions = decisions;
        this.dedupNote = note;
    }

    void markRejected(String reviewer, Instant at, String note) {
        this.status = Status.REJECTED;
        this.decidedBy = reviewer;
        this.decidedAt = at;
        this.dedupNote = note;
    }

    /**
     * 去重键：同一对已有患者（无序）+ 同一类冲突，未决期间只保留一条待办。
     */
    public String dedupKey() {
        String lo = mpiIdA.compareTo(mpiIdB) <= 0 ? mpiIdA : mpiIdB;
        String hi = lo.equals(mpiIdA) ? mpiIdB : mpiIdA;
        return lo + "|" + hi + "|" + conflictCategory;
    }
}
