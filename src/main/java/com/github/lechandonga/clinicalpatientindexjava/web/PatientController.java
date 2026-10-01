package com.github.lechandonga.clinicalpatientindexjava.web;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditEvent;
import com.github.lechandonga.clinicalpatientindexjava.audit.AuditLog;
import com.github.lechandonga.clinicalpatientindexjava.privacy.DeidentifiedPatientView;
import com.github.lechandonga.clinicalpatientindexjava.security.ApiAuthenticator;
import com.github.lechandonga.clinicalpatientindexjava.security.Caller;
import com.github.lechandonga.clinicalpatientindexjava.service.IngestionService;
import com.github.lechandonga.clinicalpatientindexjava.service.PatientQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class PatientController {

    private final ApiAuthenticator authenticator;
    private final IngestionService ingestionService;
    private final PatientQueryService queryService;
    private final AuditLog auditLog;

    public PatientController(ApiAuthenticator authenticator,
                             IngestionService ingestionService,
                             PatientQueryService queryService,
                             AuditLog auditLog) {
        this.authenticator = authenticator;
        this.ingestionService = ingestionService;
        this.queryService = queryService;
        this.auditLog = auditLog;
    }

    @PostMapping(path = "/api/patients/ingest")
    public ResponseEntity<?> ingest(@RequestBody(required = false) String body,
                                    HttpServletRequest request) {
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_INGEST);
        IngestionService.IngestResponse response =
                ingestionService.ingest(request.getContentType(), body, caller.username());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/patients")
    public ResponseEntity<?> queryBySourceIdentifier(
            @RequestParam String system,
            @RequestParam String identifier,
            HttpServletRequest request) {
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_QUERY);
        DeidentifiedPatientView view =
                queryService.queryByIdentifier(caller, system, identifier);
        if (view == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "PATIENT_NOT_FOUND"));
        }
        return ResponseEntity.ok(view);
    }

    @GetMapping("/api/patients/{mpiId}")
    public ResponseEntity<?> queryByMpi(@PathVariable String mpiId,
                                        HttpServletRequest request) {
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_QUERY);
        DeidentifiedPatientView view = queryService.queryByMpi(caller, mpiId);
        if (view == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "PATIENT_NOT_FOUND"));
        }
        return ResponseEntity.ok(view);
    }

    @GetMapping("/api/audit/events")
    public List<AuditEvent> auditEvents(HttpServletRequest request) {
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_AUDIT_READ);
        return auditLog.findAll();
    }

    private Caller requireScope(HttpServletRequest request, String scope) {
        Caller caller;
        try {
            caller = authenticator.authenticate(request);
        } catch (ApiAuthenticator.SecurityException e) {
            throw new WebSecurityException(e.getFailure(), e.getMessage());
        }
        if (caller == null) {
            throw new WebSecurityException(ApiAuthenticator.AuthFailure.MISSING_TOKEN,
                    "Authorization: Bearer <token> header is required");
        }
        if (!caller.hasScope(scope)) {
            throw new WebSecurityException(ApiAuthenticator.AuthFailure.FORBIDDEN_SCOPE,
                    "caller '" + caller.username() + "' lacks scope " + scope);
        }
        return caller;
    }

    public static class WebSecurityException extends RuntimeException {
        private final ApiAuthenticator.AuthFailure failure;

        public WebSecurityException(ApiAuthenticator.AuthFailure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public ApiAuthenticator.AuthFailure getFailure() {
            return failure;
        }
    }
}
