package com.github.lechandonga.clinicalpatientindexjava.domain;

/**
 * 归一后的性别。无法解释的输入映射为 UNKNOWN，不丢弃数据。
 */
public enum Gender {
    MALE,
    FEMALE,
    OTHER,
    UNKNOWN;

    public static Gender normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        return switch (raw.trim().toUpperCase()) {
            case "M", "MALE", "1" -> MALE;
            case "F", "FEMALE", "2" -> FEMALE;
            case "O", "OTHER", "3" -> OTHER;
            default -> UNKNOWN;
        };
    }
}
