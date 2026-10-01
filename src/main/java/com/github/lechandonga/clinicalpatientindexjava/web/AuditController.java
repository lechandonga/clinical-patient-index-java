package com.github.lechandonga.clinicalpatientindexjava.web;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditEvent;
import com.github.lechandonga.clinicalpatientindexjava.audit.AuditService;
import com.github.lechandonga.clinicalpatientindexjava.security.ClientPrincipal;
import com.github.lechandonga.clinicalpatientindexjava.security.SecurityInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/events")
    public List<AuditEvent> events(HttpServletRequest request) {
        ClientPrincipal principal = (ClientPrincipal) request
                .getAttribute(SecurityInterceptor.PRINCIPAL_ATTRIBUTE);
        return auditService.list(principal.clientId());
    }
}
