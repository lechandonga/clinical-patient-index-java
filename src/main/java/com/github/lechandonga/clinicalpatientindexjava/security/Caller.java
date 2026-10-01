package com.github.lechandonga.clinicalpatientindexjava.security;

import java.util.Set;

/**
 * 已认证调用方。
 *
 * @param username 用户名
 * @param scopes   权限范围
 */
public record Caller(String username, Set<String> scopes) {

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
