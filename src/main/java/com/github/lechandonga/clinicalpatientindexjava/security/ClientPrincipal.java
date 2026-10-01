package com.github.lechandonga.clinicalpatientindexjava.security;

import java.util.Set;

/**
 * 通过认证的调用方身份。
 *
 * @param clientId 调用方标识（写入审计日志，非患者敏感字段）
 * @param roles    被授予的角色
 * @param systems  允许访问的标识符系统白名单，空集合表示不限制
 */
public record ClientPrincipal(String clientId, Set<Role> roles, Set<String> systems) {

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public boolean systemAllowed(String normalizedSystem) {
        return systems.isEmpty() || systems.contains(normalizedSystem);
    }
}
