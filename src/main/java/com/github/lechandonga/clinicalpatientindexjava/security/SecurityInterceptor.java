package com.github.lechandonga.clinicalpatientindexjava.security;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditService;
import com.github.lechandonga.clinicalpatientindexjava.ingest.ErrorCode;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IngestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 在控制器前完成认证与粗粒度角色授权。
 * 来源系统级别的细粒度授权在服务层依据输入/查询参数判定。
 */
@Component
public class SecurityInterceptor implements HandlerInterceptor {

    public static final String PRINCIPAL_ATTRIBUTE = "cpi.principal";

    private final ApiKeyService apiKeyService;
    private final AuditService auditService;

    public SecurityInterceptor(ApiKeyService apiKeyService, AuditService auditService) {
        this.apiKeyService = apiKeyService;
        this.auditService = auditService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        ClientPrincipal principal;
        try {
            principal = apiKeyService.authenticate(request.getHeader(ApiKeyService.API_KEY_HEADER));
        } catch (IngestException e) {
            auditService.record(AuditService.ACCESS_DENIED, null, path,
                    "AUTHENTICATION_FAILED", e.getCode().name());
            throw e;
        }
        Role required = requiredRole(path);
        if (required != null && !principal.hasRole(required)) {
            auditService.record(AuditService.ACCESS_DENIED, principal.clientId(), path,
                    "AUTHORIZATION_FAILED", "MISSING_ROLE_" + required.name());
            throw new IngestException(ErrorCode.FORBIDDEN_ROLE,
                    "调用方缺少角色 " + required.name());
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        return true;
    }

    private static Role requiredRole(String path) {
        if (path.startsWith("/api/ingest")) {
            return Role.INGEST;
        }
        if (path.startsWith("/api/patients")) {
            return Role.QUERY;
        }
        if (path.startsWith("/api/audit")) {
            return Role.AUDIT_READ;
        }
        return null;
    }
}
