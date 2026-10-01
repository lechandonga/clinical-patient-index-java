package com.github.lechandonga.clinicalpatientindexjava.deid;

import com.github.lechandonga.clinicalpatientindexjava.config.CpiProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 去标识化原语。
 * <ul>
 *     <li>{@link #pseudonym}：HMAC-SHA256 派生稳定伪名。同一规范化键 + 同一密钥永远得到同一伪名，
 *     跨输出保持一致；密钥改变后伪名整体轮换，且不可由伪名反推明文。</li>
 *     <li>{@link #fingerprint}：不带密钥的截断 SHA-256，仅用于日志/审计输入摘要。</li>
 * </ul>
 */
@Component
public class DeIdentificationService {

    private final byte[] secret;

    public DeIdentificationService(CpiProperties properties) {
        this.secret = properties.getDeidentification().getSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String pseudonym(String canonicalKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] hash = mac.doFinal(canonicalKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 初始化失败", e);
        }
    }

    public static String fingerprint(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
