package com.github.lechandonga.clinicalpatientindexjava.security;

/**
 * 权限角色。
 * <ul>
 *     <li>{@link #INGEST}：允许提交 HL7 / FHIR 病历数据。</li>
 *     <li>{@link #QUERY}：允许调用去标识化查询接口。</li>
 *     <li>{@link #AUDIT_READ}：允许读取审计日志。</li>
 * </ul>
 */
public enum Role {
    INGEST,
    QUERY,
    AUDIT_READ
}
