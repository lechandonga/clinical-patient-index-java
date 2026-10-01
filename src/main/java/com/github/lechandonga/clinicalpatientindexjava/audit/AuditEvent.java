package com.github.lechandonga.clinicalpatientindexjava.audit;

/**
 * 单条审计事件。
 * <p>敏感字段约束：只允许出现伪名/摘要，不允许姓名、证件号、联系方式或原始消息明文。
 *
 * @param timestampEpochMillis 访问时间（毫秒）
 * @param clientId             调用方标识；认证失败时为 null
 * @param action               动作类别（INGEST / QUERY / AUDIT_READ / ACCESS_DENIED）
 * @param path                 请求路径
 * @param targetPseudonym      被访问患者的稳定伪名；无具体目标时为 null
 * @param decision             ALLOWED / DENIED
 * @param reasonCode           判定依据（错误码或规则名）
 * @param inputDigest          输入的非可逆摘要，用于关联具体提交
 */
public record AuditEvent(long timestampEpochMillis,
                         String clientId,
                         String action,
                         String path,
                         String targetPseudonym,
                         String decision,
                         String reasonCode,
                         String inputDigest) {
}
