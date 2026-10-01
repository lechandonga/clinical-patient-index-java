package com.github.lechandonga.clinicalpatientindexjava.domain;

import java.util.Objects;

/**
 * 来自某源系统的患者标识符。
 *
 * @param system 标识符域/分配系统，如 HOSPITAL_A、HOSPITAL_B
 * @param value  源系统内的标识符值
 */
public record PatientIdentifier(String system, String value) {

    public PatientIdentifier {
        Objects.requireNonNull(system, "identifier system");
        Objects.requireNonNull(value, "identifier value");
    }

    /**
     * 统一表示：系统与值均归一为去空白、大写、去常见前缀后比较。
     */
    public String canonicalKey() {
        return normalizeSystem(system) + "|" + stripCommonPrefixes(value).toUpperCase().replaceAll("\\s+", "");
    }

    public static String normalizeSystem(String system) {
        return system.trim().toUpperCase().replaceAll("\\s+", "");
    }

    /**
     * 去掉常见的大小写不敏感前缀，如 MRN-、MRN:、PAT-、ID- 等。
     */
    public static String stripCommonPrefixes(String value) {
        String v = value.trim();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^(?:MRN|PAT|PID|ID|NO|NUM)[\\-_:#]?\\s*",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(v);
        if (matcher.find()) {
            v = v.substring(matcher.end());
        }
        return v.isBlank() ? value.trim() : v;
    }
}
