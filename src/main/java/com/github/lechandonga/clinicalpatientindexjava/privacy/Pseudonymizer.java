package com.github.lechandonga.clinicalpatientindexjava.privacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 去标识化服务。
 *
 * 替代标识 = HMAC-SHA256(密钥, mpiId) 的截断十六进制，同一患者在任意次、
 * 任意输出中的替代标识稳定一致；无密钥无法反推 MPI 或明文。
 * 标识符哈希（SHA-256，截断）仅供审计关联，不携带明文。
 */
@Component
public class Pseudonymizer {

    private final byte[] secret;

    public Pseudonymizer(@Value("${mpi.pseudonym-secret:local-development-secret-change-me}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String pseudonym(String mpiId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal(mpiId.getBytes(StandardCharsets.UTF_8));
            return "PSEU-" + HexFormat.of().formatHex(raw, 0, 12);
        } catch (Exception e) {
            throw new IllegalStateException("pseudonymization failed", e);
        }
    }

    public String hashIdentifier(String canonicalIdentifierKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest(canonicalIdentifierKey.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(raw, 0, 10);
        } catch (Exception e) {
            throw new IllegalStateException("identifier hashing failed", e);
        }
    }
}
