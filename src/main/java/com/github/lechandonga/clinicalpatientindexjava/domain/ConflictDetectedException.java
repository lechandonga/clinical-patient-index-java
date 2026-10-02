package com.github.lechandonga.clinicalpatientindexjava.domain;

import java.util.List;

/**
 * 归并护栏检测到两个人口学冲突的已有簇时抛出。
 * 携带的 MPI ID 仅供内部生成复核待办使用；对外 detail 不得包含人口学明文。
 */
public class ConflictDetectedException extends IngestionException {

    private final String survivorMpiId;
    private final String conflictingMpiId;
    private final String conflictCategory;

    public ConflictDetectedException(String survivorMpiId,
                                    String conflictingMpiId,
                                    String conflictCategory,
                                    String safeDetail) {
        super(RejectionReason.CONTRADICTORY_DATA, safeDetail);
        this.survivorMpiId = survivorMpiId;
        this.conflictingMpiId = conflictingMpiId;
        this.conflictCategory = conflictCategory;
    }

    public String getSurvivorMpiId() {
        return survivorMpiId;
    }

    public String getConflictingMpiId() {
        return conflictingMpiId;
    }

    public String getConflictCategory() {
        return conflictCategory;
    }

    public List<String> involvedMpiIds() {
        return List.of(survivorMpiId, conflictingMpiId);
    }
}
