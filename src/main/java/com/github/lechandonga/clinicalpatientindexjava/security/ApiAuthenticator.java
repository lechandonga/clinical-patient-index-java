package com.github.lechandonga.clinicalpatientindexjava.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 本地 Bearer Token 鉴权。
 * 令牌通过配置管理，默认内置一组便于本地验证的令牌（生产应覆盖配置）。
 * 失败原因区分：未提供凭证 / 凭证无效 / 权限不足。
 */
@Component
public class ApiAuthenticator {

    public static final String SCOPE_INGEST = "patient:ingest";
    public static final String SCOPE_QUERY = "patient:query";
    public static final String SCOPE_AUDIT_READ = "audit:read";
    public static final String SCOPE_REVIEW = "patient:review";

    private final Map<String, Caller> tokenStore = new LinkedHashMap<>();

    public ApiAuthenticator(@Value("${mpi.tokens:}") String configuredTokens) {
        tokenStore.put("ingest-token-a",
                new Caller("source-a", Set.of(SCOPE_INGEST)));
        tokenStore.put("ingest-token-b",
                new Caller("source-b", Set.of(SCOPE_INGEST)));
        tokenStore.put("query-token",
                new Caller("analyst", Set.of(SCOPE_QUERY)));
        tokenStore.put("admin-token",
                new Caller("admin", Set.of(SCOPE_QUERY, SCOPE_AUDIT_READ)));
        tokenStore.put("reviewer-token",
                new Caller("reviewer", Set.of(SCOPE_REVIEW)));
        parseConfigured(configuredTokens);
    }

    private void parseConfigured(String configuredTokens) {
        if (configuredTokens == null || configuredTokens.isBlank()) {
            return;
        }
        for (String entry : configuredTokens.split(";")) {
            String[] parts = entry.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String[] nameAndScopes = parts[1].split(",", 2);
            String name = nameAndScopes[0].trim();
            Set<String> scopes = nameAndScopes.length == 2
                    ? java.util.Arrays.stream(nameAndScopes[1].split(","))
                            .map(String::trim).filter(s -> !s.isEmpty())
                            .collect(java.util.stream.Collectors.toUnmodifiableSet())
                    : Set.of();
            tokenStore.put(parts[0].trim(), new Caller(name, scopes));
        }
    }

    /**
     * @return 认证通过的调用方；未携带令牌返回 null
     * @throws SecurityException 令牌非法时抛出，原因 INVALID_TOKEN
     */
    public Caller authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            return null;
        }
        if (!header.startsWith("Bearer ")) {
            throw new SecurityException(AuthFailure.INVALID_TOKEN.name(),
                    "authorization scheme must be Bearer");
        }
        String token = header.substring("Bearer ".length()).trim();
        Caller caller = tokenStore.get(token);
        if (caller == null) {
            throw new SecurityException(AuthFailure.INVALID_TOKEN.name(),
                    "token is not recognized");
        }
        return caller;
    }

    public enum AuthFailure {
        MISSING_TOKEN,
        INVALID_TOKEN,
        FORBIDDEN_SCOPE
    }

    public static class SecurityException extends RuntimeException {
        private final AuthFailure failure;

        public SecurityException(String failureName, String message) {
            super(message);
            this.failure = AuthFailure.valueOf(failureName);
        }

        public AuthFailure getFailure() {
            return failure;
        }
    }
}
