package com.github.lechandonga.clinicalpatientindexjava.web;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditEvent;
import com.github.lechandonga.clinicalpatientindexjava.audit.AuditLog;
import com.github.lechandonga.clinicalpatientindexjava.privacy.DeidentifiedPatientView;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewService;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewTicket;
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
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_INGEST);
        IngestionService.IngestResponse response =
                ingestionService.ingest(request.getContentType(), body, caller.username());
        return ResponseEntity.ok(response);
    }

    /**
     * 接入预检：与正式接入同凭证、同规则、同拒绝原因，但完全只读。
     */
    @PostMapping(path = "/api/patients/precheck")
    public ResponseEntity<?> precheck(@RequestBody(required = false) String body,
                                      HttpServletRequest request) {
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_INGEST, "PRECHECK");
        IngestionService.PrecheckResponse response =
                ingestionService.precheck(request.getContentType(), body, caller.username());
        return ResponseEntity.ok(response);
    }

    /**
     * 列出未裁决的冲突待办（仅去标识化信息）。
     */
    @GetMapping("/api/reviews/tickets")
    public List<ReviewTicketView> listReviewTickets(HttpServletRequest request) {
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_REVIEW, "REVIEW_LIST");
        return reviewService.listPending().stream()
                .map(t -> ReviewTicketView.of(t, reviewService.occurrenceCount(t)))
                .toList();
    }

    /**
     * 裁决：body {"decision":"APPROVE"|"REJECT"}，幂等。
     */
    @PostMapping("/api/reviews/tickets/{ticketId}/decision")
    public ResponseEntity<?> decideReview(@PathVariable String ticketId,
                                          @RequestBody Map<String, String> decisionBody,
                                          HttpServletRequest request) {
        Caller caller = requireScope(request, ApiAuthenticator.SCOPE_REVIEW, "REVIEW_DECIDE");
        String decision = decisionBody == null ? null : decisionBody.get("decision");
        ReviewTicket decided;
        if ("APPROVE".equalsIgnoreCase(decision)) {
            decided = reviewService.approve(ticketId, caller.username());
        } else if ("REJECT".equalsIgnoreCase(decision)) {
            decided = reviewService.reject(ticketId, caller.username());
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "INVALID_DECISION",
                    "detail", "decision must be APPROVE or REJECT"));
        }
        return ResponseEntity.ok(ReviewTicketView.of(decided, reviewService.occurrenceCount(decided)));
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
        return requireScope(request, scope, null);
    }

    private Caller requireScope(HttpServletRequest request, String scope, String auditedAction) {
        Caller caller;
        ApiAuthenticator.AuthFailure failure;
        String actor = "anonymous";
        try {
            caller = authenticator.authenticate(request);
        } catch (ApiAuthenticator.SecurityException e) {
            failure = e.getFailure();
            auditAuthFailure(auditedAction, actor, failure);
            throw new WebSecurityException(failure, e.getMessage());
        }
        if (caller == null) {
            failure = ApiAuthenticator.AuthFailure.MISSING_TOKEN;
            auditAuthFailure(auditedAction, actor, failure);
            throw new WebSecurityException(failure,
                    "Authorization: Bearer <token> header is required");
        }
        actor = caller.username();
        if (!caller.hasScope(scope)) {
            failure = ApiAuthenticator.AuthFailure.FORBIDDEN_SCOPE;
            auditAuthFailure(auditedAction, actor, failure);
            throw new WebSecurityException(failure,
                    "caller '" + caller.username() + "' lacks scope " + scope);
        }
        return caller;
    }

    private void auditAuthFailure(String auditedAction, String actor,
                                  ApiAuthenticator.AuthFailure failure) {
        if (auditedAction == null) {
            return;
        }
        auditLog.record(actor, auditedAction, null, null, null,
                "DENIED:" + failure.name(),
                "authorization failed (no plaintext retained)");
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
