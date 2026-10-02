package com.github.lechandonga.clinicalpatientindexjava.review;

import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex.FieldDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 冲突人工复核待办队列（内存实现）。
 *
 * <ul>
 *   <li>同一对已有患者 + 同一类冲突在未决（PENDING）期间反复提交不重复堆积，
 *       返回同一条待办并累加 occurrenceCount；</li>
 *   <li>PENDING 待办数量有可配置上限（mpi.review.max-pending，默认 100），
 *       满后新冲突照常拒绝并抛出 {@link ReviewQueueFullException}，不静默丢弃；</li>
 *   <li>裁决（批准/驳回）由 {@link ReviewService} 经 {@link #casDecide} 做
 *       compare-and-set，并发/重复裁决只生效一次，重复调用返回相同结果。</li>
 * </ul>
 */
@Component
public class ReviewQueue {

    private final int maxPending;
    private final ConcurrentMap<String, ReviewTicket> ticketsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> pendingDedup = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> occurrenceCounts = new ConcurrentHashMap<>();
    private final AtomicLong ticketSequence = new AtomicLong();
    private final Object enqueueLock = new Object();

    public ReviewQueue(@Value("${mpi.review.max-pending:100}") int maxPending) {
        this.maxPending = maxPending;
    }

    public int getMaxPending() {
        return maxPending;
    }

    /**
     * 登记一条冲突待办。未决去重；队列满抛 {@link ReviewQueueFullException}。
     */
    public ReviewTicket register(String mpiIdA,
                                 String mpiIdB,
                                 String pseudonymA,
                                 String pseudonymB,
                                 String conflictCategory,
                                 String submittedBy) {
        String probeKey = dedupKey(mpiIdA, mpiIdB, conflictCategory);
        String existingId = pendingDedup.get(probeKey);
        if (existingId != null) {
            ReviewTicket existing = ticketsById.get(existingId);
            if (existing == null || existing.getStatus() != ReviewTicket.Status.PENDING) {
                pendingDedup.remove(probeKey, existingId);
            }
        }
        synchronized (enqueueLock) {
            String currentId = pendingDedup.get(probeKey);
            if (currentId != null) {
                ReviewTicket current = ticketsById.get(currentId);
                if (current != null && current.getStatus() == ReviewTicket.Status.PENDING) {
                    occurrenceCounts.get(probeKey).incrementAndGet();
                    return current;
                }
                pendingDedup.remove(probeKey, currentId);
            }
            if (countPending() >= maxPending) {
                throw new ReviewQueueFullException(maxPending);
            }
            String ticketId = String.format("REV-%010d", ticketSequence.incrementAndGet());
            ReviewTicket ticket = new ReviewTicket(ticketId, mpiIdA, mpiIdB,
                    pseudonymA, pseudonymB, conflictCategory, submittedBy, Instant.now());
            ticketsById.put(ticketId, ticket);
            pendingDedup.put(probeKey, ticketId);
            occurrenceCounts.put(probeKey, new AtomicLong(1));
            return ticket;
        }
    }

    public int countPending() {
        return pendingDedup.size();
    }

    public long occurrenceCount(ReviewTicket ticket) {
        AtomicLong count = occurrenceCounts.get(
                dedupKey(ticket.getMpiIdA(), ticket.getMpiIdB(), ticket.getConflictCategory()));
        return count == null ? 0 : count.get();
    }

    public List<ReviewTicket> findByStatus(ReviewTicket.Status status) {
        return ticketsById.values().stream()
                .filter(t -> t.getStatus() == status)
                .sorted(Comparator.comparing(ReviewTicket::getSubmittedAt))
                .toList();
    }

    public Optional<ReviewTicket> findById(String ticketId) {
        return Optional.ofNullable(ticketsById.get(ticketId));
    }

    /**
     * 仅供本地测试隔离使用：清空全部待办状态。
     */
    public void resetForTesting() {
        synchronized (enqueueLock) {
            ticketsById.clear();
            pendingDedup.clear();
            occurrenceCounts.clear();
            ticketSequence.set(0);
        }
    }

    /**
     * compare-and-set 裁决：仅当待办仍为 PENDING 时由唯一胜出线程执行 mutator，
     * 其余线程（以及之后的重复调用）看到同一结果。
     *
     * @return 裁决后的待办（内容与第一次裁决完全一致）
     */
    public ReviewTicket casDecide(String ticketId, DecisionMutation mutation) {
        ReviewTicket ticket = ticketsById.get(ticketId);
        if (ticket == null) {
            return null;
        }
        synchronized (ticket) {
            if (ticket.getStatus() == ReviewTicket.Status.PENDING) {
                mutation.apply(ticket);
                pendingDedup.remove(
                        dedupKey(ticket.getMpiIdA(), ticket.getMpiIdB(), ticket.getConflictCategory()));
            }
            return ticket;
        }
    }

    private static String dedupKey(String mpiIdA, String mpiIdB, String conflictCategory) {
        String lo = mpiIdA.compareTo(mpiIdB) <= 0 ? mpiIdA : mpiIdB;
        String hi = lo.equals(mpiIdA) ? mpiIdB : mpiIdA;
        return lo + "|" + hi + "|" + conflictCategory;
    }

    @FunctionalInterface
    public interface DecisionMutation {
        void apply(ReviewTicket ticket);
    }
}
