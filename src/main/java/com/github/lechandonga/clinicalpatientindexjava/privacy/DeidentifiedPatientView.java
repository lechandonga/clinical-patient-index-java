package com.github.lechandonga.clinicalpatientindexjava.privacy;

/**
 * 对外查询视图：只包含非直接标识信息。
 * 不包含姓名、证件号、联系方式、原始标识符值。
 */
public record DeidentifiedPatientView(
        String pseudonym,
        String gender,
        Integer birthYear,
        int linkedIdentifierCount,
        int linkedSourceSystemCount) {
}
