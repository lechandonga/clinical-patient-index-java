package com.github.lechandonga.clinicalpatientindexjava.web;

import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex.FieldDecision;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewTicket;

import java.time.Instant;
import java.util.List;

/**
 * 待办对外视图：仅替代标识、类别、提交者、时间与裁决状态/字段取舍依据，
 * 不含姓名、原始标识符、出生日期等明文敏感信息。
 */
public record ReviewTicketView(String ticketId,
                               String status,
                               String conflictCategory,
                               String pseudonymA,
                               String pseudonymB,
                               String submittedBy,
                               Instant submittedAt,
                               String decidedBy,
                               Instant decidedAt,
                               long occurrenceCount,
                               List<String> fieldDecisions,
                               String decisionNote) {

    public static ReviewTicketView of(ReviewTicket ticket, long occurrenceCount) {
        List<String> decisions = ticket.getMergeDecisions() == null ? List.of()
                : ticket.getMergeDecisions().stream()
                        .map(d -> d.field() + ":" + d.effect() + ":" + d.source()
                                + " (" + d.basis() + ")")
                        .toList();
        return new ReviewTicketView(ticket.getTicketId(), ticket.getStatus().name(),
                ticket.getConflictCategory(), ticket.getPseudonymA(), ticket.getPseudonymB(),
                ticket.getSubmittedBy(), ticket.getSubmittedAt(), ticket.getDecidedBy(),
                ticket.getDecidedAt(), occurrenceCount, decisions, ticket.getDedupNote());
    }
}
