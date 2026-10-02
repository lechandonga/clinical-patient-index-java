package com.github.lechandonga.clinicalpatientindexjava.review;

import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;

/**
 * 复核待办队列已满。对外仍是冲突拒绝（409），但原因码明确为 REVIEW_QUEUE_FULL，
 * 与一般的 CONTRADICTORY_DATA 区分，绝不静默丢弃。
 */
public class ReviewQueueFullException extends IngestionException {

    public ReviewQueueFullException(int limit) {
        super(RejectionReason.REVIEW_QUEUE_FULL,
                "conflict rejected and manual review backlog is full (limit=" + limit
                        + "); no ticket was created, retry after a reviewer resolves pending tickets");
    }
}
