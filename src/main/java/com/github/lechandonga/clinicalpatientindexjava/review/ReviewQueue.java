package com.github.lechandonga.clinicalpatientindexjava.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 冲突复核待办队列（内存，本地验证用）。
 *
 * <p>规则：
 * <ul>
 *   <li>同一对（或同一个）主索引 + 同一冲突类型在未决期间只保留一条待办，
 *       反复提交只增加 submissionCount，不堆记录；</li>
 *   <li>待办数量有可配置上限，满了之后不再登记，由上层以
 *       {@code REVIEW_QUEUE_FULL} 明确拒绝（不静默丢弃）；</li>
 *   <li>原始归一消息只存在进程内暂存区 {@link ReviewStagingStore}，
 *       绝不会出现在待办记录、对外响应与审计中。</li>
 * </ul>
 * 本队列自身不加锁：所有调用都在主索引归并锁内完成（见 ReviewService），
 * 从而保证“冲突拒绝/待办登记/裁决/新接入”串行化，不存在交叉的中间态。
 */
public class ReviewQueue {

    private final int limit;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, ReviewTask> openByDedupKey = new LinkedHashMap<>();
    private final Map<String, ReviewTask> tasksById = new LinkedHashMap<>();

    public ReviewQueue(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("review queue limit must be >= 1");
        }
        this.limit = limit;
    }

    public int getLimit() {
        return limit;
    }

    public int openCount() {
        return openByDedupKey.size();
    }

    /**
     * 登记一条新待办。调用方须先用 {@link #findOpenByDedupKey(String)} 判断未决去重，
     * 并用 {@link #openCount()} 判断上限。队列满时返回 null，由上层转为
     * REVIEW_QUEUE_FULL，绝不静默丢弃。
     */
    public ReviewTask add(ReviewTask task, ReviewStagingStore stagingStore,
                          com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient payload) {
        if (openByDedupKey.containsKey(task.getDedupKey()) || openByDedupKey.size() >= limit) {
            return null;
        }
        openByDedupKey.put(task.getDedupKey(), task);
        tasksById.put(task.getId(), task);
        stagingStore.put(task.getId(), payload);
        return task;
    }

    public ReviewTask findOpenByDedupKey(String dedupKey) {
        return openByDedupKey.get(dedupKey);
    }

    public String nextId() {
        return String.format("REV-%010d", sequence.incrementAndGet());
    }

    public void recordResubmission(String dedupKey, String actor, java.time.Instant now) {
        ReviewTask existing = openByDedupKey.get(dedupKey);
        if (existing != null) {
            existing.recordResubmission(actor, now);
        }
    }

    /**
     * 裁决：原子地把待办从 OPEN 集合移除并返回；若已被裁决返回 null。
     */
    public ReviewTask settle(ReviewTask task) {
        return openByDedupKey.remove(task.getDedupKey()) == null ? null : task;
    }

    public ReviewTask findById(String id) {
        return tasksById.get(id);
    }

    public List<ReviewTask> findOpen() {
        return new ArrayList<>(openByDedupKey.values());
    }
}
