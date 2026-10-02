package com.github.lechandonga.clinicalpatientindexjava.web;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditEvent;
import com.github.lechandonga.clinicalpatientindexjava.audit.AuditLog;
import com.github.lechandonga.clinicalpatientindexjava.privacy.DeidentifiedPatientView;
import com.github.lechandonga.clinicalpatientindexjava.security.ApiAuthenticator;
import com.github.lechandonga.clinicalpatientindexjava.security.Caller;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewTask;
import com.github.lechandonga.clinicalpatientindexjava.service.IngestionService;
import com.github.lechandonga.clinicalpatientindexjava.service.PatientQueryService;
import com.github.lechandonga.clinicalpatientindexjava.service.ReviewService;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
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
    private final ReviewService reviewService;

    public PatientController(ApiAuthenticator authenticator,
                             IngestionService ingestionService,
                             PatientQueryService queryService,
                             AuditLog auditLog,
                             ReviewService reviewService) {
        this.authenticator = authenticator;
        this.ingestionService = ingestionService;
        this.queryService = queryService;
        this.auditLog = auditLog;
        this.reviewService = reviewService;
    }

    @PostMapping(path = "/api/patients/ingest")
    public ResponseEntity<?> ingest(@RequestBody(required = false) String body,
                                    HttpServletRequest request) {
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_INGEST, "INGEST");
        IngestionService.IngestResponse response =
                ingestionService.ingest(request.getContentType(), body, caller.username());
        return ResponseEntity.ok(response);
    }

    /**
     * 接入预检：需要与正式接入相同的 ingest 凭证；完全只读。
     */
    @PostMapping(path = "/api/patients/preview")
    public ResponseEntity<?> preview(@RequestBody(required = false) String body,
                                     HttpServletRequest request) {
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_INGEST, "PREVIEW");
        ReviewService.PreviewResult result =
                ingestionService.preview(request.getContentType(), body, caller.username());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/patients")
    public ResponseEntity<?> queryBySourceIdentifier(
            @RequestParam String system,
            @RequestParam String identifier,
            HttpServletRequest request) {
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_QUERY, "QUERY");
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
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_QUERY, "QUERY");
        DeidentifiedPatientView view = queryService.queryByMpi(caller, mpiId);
        if (view == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "PATIENT_NOT_FOUND"));
        }
        return ResponseEntity.ok(view);
    }

    @GetMapping("/api/audit/events")
    public List<AuditEvent> auditEvents(HttpServletRequest request) {
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_AUDIT_READ, "AUDIT_READ");
        return auditLog.findAll();
    }

    @GetMapping("/api/reviews")
    public List<ReviewTask> listReviews(HttpServletRequest request) {
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_REVIEW, "REVIEW_LIST");
        return reviewService.listOpen(caller.username());
    }

    /**
     * 复核裁决。body 为 {"decision":"APPROVE"} 或 {"decision":"REJECT"}。
     * 同样的裁决重复/并发调用只生效一次，且返回相同结果；相反裁决被拒绝。
     */
    @PostMapping("/api/reviews/{reviewId}/decision")
    public ResponseEntity<?> decide(@PathVariable String reviewId,
                                    @RequestBody(required = false) String body,
                                    HttpServletRequest request) {
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_REVIEW, "REVIEW_DECIDE");
        String decision = extractDecision(body);
        if (decision == null) {
            auditLog.record(caller.username(), "REVIEW_DECIDE", null, null, null,
                    "REJECTED:INVALID_DECISION", "task=" + reviewId);
            throw new IngestionException(IngestionException.RejectionReason.INVALID_DECISION,
                    "request body must be {\"decision\":\"APPROVE\"} or {\"decision\":\"REJECT\"}");
        }
        ReviewService.DecisionResult result =
                reviewService.decide(reviewId, "APPROVE".equals(decision), caller.username());
        return ResponseEntity.ok(result);
    }

    private static String extractDecision(String body) {
        if (body == null) {
            return null;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"decision\"\\s*:\\s*\"([A-Z]+)\"")
                        .matcher(body);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        return value.equals("APPROVE") || value.equals("REJECT") ? value : null;
    }

    private Caller requireScope(HttpServletRequest request, String scope, String action) {
        Caller caller;
        try {
            caller = authenticator.authenticate(request);
        } catch (ApiAuthenticator.SecurityException e) {
            auditLog.record("anonymous", action, null, null, null,
                    "REJECTED:" + e.getFailure(), "authentication failed (no plaintext recorded)");
            throw new WebSecurityException(e.getFailure(), e.getMessage());
        }
        if (caller == null) {
            auditLog.record("anonymous", action, null, null, null,
                    "REJECTED:MISSING_TOKEN", "no bearer token (no plaintext recorded)");
            throw new WebSecurityException(ApiAuthenticator.AuthFailure.MISSING_TOKEN,
                    "Authorization: Bearer <token> header is required");
        }
        if (!caller.hasScope(scope)) {
            auditLog.record(caller.username(), action, null, null, null,
                    "REJECTED:FORBIDDEN_SCOPE", "required scope " + scope);
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
