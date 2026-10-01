package com.github.lechandonga.clinicalpatientindexjava.audit;

import java.time.Instant;

/**
 * 审计事件。仅记录伪标识/MPI 与标识符哈希，绝不包含姓名、证件号等明文敏感字段。
 */
public record AuditEvent(
        Instant timestamp,
        String actor,
        String action,
        String targetMpiId,
        String targetPseudonym,
        String identifierHash,
        String result,
        String detail) {
}
