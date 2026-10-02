package com.github.lechandonga.clinicalpatientindexjava.review;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditLog;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex.FieldDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 冲突复核裁决编排：
 * <ul>
 *   <li>批准：在复核队列 CAS 胜出后调用主索引强制合并，二者串行完成；
 *       重复/并发批准只有第一次生效，后续调用返回相同裁决结果；</li>
 *   <li>驳回：不触碰主索引，两个簇的标识归属与人口学原封不动；</li>
 *   <li>裁决与新接入共用主索引的同一把归并锁，不会出现重复主索引、
 *       数据覆盖或半合并状态。</li>
 * </ul>
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewQueue queue;
    private final MasterPatientIndex index;
    private final AuditLog auditLog;

    public ReviewService(ReviewQueue queue, MasterPatientIndex index, AuditLog auditLog) {
        this.queue = queue;
        this.index = index;
        this.auditLog = auditLog;
    }

    public List<ReviewTicket> listPending() {
        return queue.findByStatus(ReviewTicket.Status.PENDING);
    }

    public long occurrenceCount(ReviewTicket ticket) {
        return queue.occurrenceCount(ticket);
    }

    public ReviewTicket requireTicket(String ticketId) {
        return queue.findById(ticketId).orElseThrow(() ->
                new TicketNotFoundException("review ticket not found: " + ticketId));
    }

    public ReviewTicket approve(String ticketId, String reviewer) {
        ReviewTicket existing = queue.findById(ticketId).orElse(null);
        if (existing == null) {
            throw new TicketNotFoundException("review ticket not found: " + ticketId);
        }
        if (existing.getStatus() != ReviewTicket.Status.PENDING) {
            log.info("[REVIEW IDEMPOTENT] actor={} ticket={} status={}",
                    reviewer, ticketId, existing.getStatus());
            return existing;
        }
        ReviewTicket decided = queue.casDecide(ticketId, ticket -> {
            List<FieldDecision> decisions =
                    index.forceMerge(ticket.getMpiIdA(), ticket.getMpiIdB());
            ticket.markApproved(reviewer, Instant.now(), decisions,
                    "approved merge: survivor=" + ticket.getMpiIdA()
                            + " kept existing values; absorbed=" + ticket.getMpiIdB()
                            + " only filled previously empty fields; decisions="
                            + describe(decisions));
        });
        log.info("[REVIEW APPROVE] actor={} ticket={} survivor={} absorbed={} category={}",
                reviewer, ticketId, decided.getMpiIdA(), decided.getMpiIdB(),
                decided.getConflictCategory());
        auditLog.record(reviewer, "REVIEW_APPROVE", decided.getMpiIdA(),
                decided.getPseudonymA(), null, decided.getStatus().name(),
                "ticket=" + ticketId + "; category=" + decided.getConflictCategory()
                        + "; survivorPseudonym=" + decided.getPseudonymA()
                        + "; absorbedPseudonym=" + decided.getPseudonymB()
                        + "; fieldDecisions=" + safeDecisions(decided.getMergeDecisions()));
        return decided;
    }

    public ReviewTicket reject(String ticketId, String reviewer) {
        ReviewTicket existing = queue.findById(ticketId).orElse(null);
        if (existing == null) {
            throw new TicketNotFoundException("review ticket not found: " + ticketId);
        }
        if (existing.getStatus() != ReviewTicket.Status.PENDING) {
            log.info("[REVIEW IDEMPOTENT] actor={} ticket={} status={}",
                    reviewer, ticketId, existing.getStatus());
            return existing;
        }
        ReviewTicket decided = queue.casDecide(ticketId, ticket -> ticket.markRejected(
                reviewer, Instant.now(),
                "rejected: indices remain separate, no identifier or demographic change"));
        log.info("[REVIEW REJECT] actor={} ticket={} pseudonyms={},{} category={}",
                reviewer, ticketId, decided.getPseudonymA(), decided.getPseudonymB(),
                decided.getConflictCategory());
        auditLog.record(reviewer, "REVIEW_REJECT", decided.getMpiIdA(),
                decided.getPseudonymA(), null, decided.getStatus().name(),
                "ticket=" + ticketId + "; category=" + decided.getConflictCategory()
                        + "; survivorPseudonym=" + decided.getPseudonymA()
                        + "; otherPseudonym=" + decided.getPseudonymB()
                        + "; indices kept separate unchanged");
        return decided;
    }

    private static String describe(List<FieldDecision> decisions) {
        if (decisions == null) {
            return "[]";
        }
        return decisions.stream()
                .map(d -> d.field() + "=" + d.effect() + "(" + d.source() + ")")
                .toList().toString();
    }

    /**
     * 审计里的字段取舍只保留字段名/动作/来源，不放任何人口学明文值。
     */
    private static String safeDecisions(List<FieldDecision> decisions) {
        return describe(decisions);
    }

    public static class TicketNotFoundException extends RuntimeException {
        public TicketNotFoundException(String message) {
            super(message);
        }
    }
}
