package com.github.lechandonga.clinicalpatientindexjava.domain;

/**
 * 归一后的患者标识符。
 *
 * @param system    规范化来源系统（大写、无空白），如 HIS
 * @param value     规范化标识符值（大写、仅字母数字、无前导零）
 * @param typeCode  标识符类型代码，如 MR / SSN / ID
 * @param rawDigest 原始值的非可逆摘要，仅用于日志/审计追溯，不含明文
 */
public record NormalizedIdentifier(String system, String value, String typeCode, String rawDigest) {

    /** 全局统一键：同一系统的同一标识符永远落到同一个键。 */
    public String canonicalKey() {
        return system + "|" + value;
    }
}
