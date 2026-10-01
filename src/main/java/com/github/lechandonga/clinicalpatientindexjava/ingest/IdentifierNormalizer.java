package com.github.lechandonga.clinicalpatientindexjava.ingest;

import com.github.lechandonga.clinicalpatientindexjava.config.CpiProperties;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedIdentifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 标识符归一规则（全部显式、可解释）：
 * <ol>
 *     <li>去除首尾空白与 UTF-8 BOM；</li>
 *     <li>系统名：空白压缩 + 大写，再按 cpi.identifier.system-aliases 归并别名；</li>
 *     <li>标识符值：大写，剥离配置的前缀/后缀，删除除字母数字外的所有分隔符，
 *         去除前导零（全为零时保留 "0"）；</li>
 *     <li>归一结果为空、含空白或超长则按 {@link ErrorCode#IDENTIFIER_INVALID} 拒绝。</li>
 * </ol>
 */
@Component
public class IdentifierNormalizer {

    private final CpiProperties properties;

    public IdentifierNormalizer(CpiProperties properties) {
        this.properties = properties;
    }

    public NormalizedIdentifier normalize(String rawSystem, String rawValue, String typeCode) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD, "标识符值缺失");
        }
        String system = normalizeSystem(rawSystem);
        if (system.isEmpty() || system.length() > 32) {
            throw new IngestException(ErrorCode.IDENTIFIER_INVALID,
                    "标识符来源系统非法（为空或超过 32 字符）: " + digest(rawSystem));
        }

        String value = stripBom(rawValue.trim()).toUpperCase();
        for (String prefix : properties.getIdentifier().getStripPrefixes()) {
            if (!prefix.isBlank() && value.startsWith(prefix.trim().toUpperCase())) {
                value = value.substring(prefix.trim().length());
                break;
            }
        }
        for (String suffix : properties.getIdentifier().getStripSuffixes()) {
            if (!suffix.isBlank() && value.endsWith(suffix.trim().toUpperCase())) {
                value = value.substring(0, value.length() - suffix.trim().length());
                break;
            }
        }
        StringBuilder compact = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                compact.append(c);
            }
        }
        value = stripLeadingZeros(compact.toString());
        if (value.isEmpty() || value.length() > properties.getIdentifier().getMaxLength()) {
            throw new IngestException(ErrorCode.IDENTIFIER_INVALID,
                    "标识符值归一后为空或超过 " + properties.getIdentifier().getMaxLength()
                            + " 字符，原始摘要=" + digest(rawValue));
        }
        String normalizedType = typeCode == null || typeCode.isBlank() ? "ID" : typeCode.trim().toUpperCase();
        return new NormalizedIdentifier(system, value, normalizedType, digest(rawValue));
    }

    public String normalizeSystem(String rawSystem) {
        String system = stripBom(rawSystem == null ? "" : rawSystem.trim())
                .replaceAll("\\s+", "_")
                .toUpperCase();
        String aliased = properties.getIdentifier().getSystemAliases().get(system);
        if (aliased != null && !aliased.isBlank()) {
            return aliased.trim().replaceAll("\\s+", "_").toUpperCase();
        }
        return system;
    }

    private static String stripLeadingZeros(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }

    /** SHA-256 截断摘要：审计/日志里只留非可逆指纹，绝不出现明文标识符。 */
    public static String digest(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String stripBom(String value) {
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }
}
