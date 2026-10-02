package com.github.lechandonga.clinicalpatientindexjava.service;

import com.github.lechandonga.clinicalpatientindexjava.privacy.Pseudonymizer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * basis 文本脱敏：形如 "matched identifier HOSPITAL_A|7001" 的条目在写入
 * 审计/预检/复核记录前，把标识符值替换为哈希，系统名保留。
 */
final class IngestionSupport {

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("([A-Z0-9_]+\\|)([^\\s\\]]+)");

    private IngestionSupport() {
    }

    static String hashIdentifierValues(String basisEntry, Pseudonymizer pseudonymizer) {
        Matcher matcher = IDENTIFIER_PATTERN.matcher(basisEntry);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb,
                    Matcher.quoteReplacement(
                            matcher.group(1)
                                    + pseudonymizer.hashIdentifier(
                                    matcher.group(1) + matcher.group(2))));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
