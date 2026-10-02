package com.github.lechandonga.clinicalpatientindexjava.web;

import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.security.ApiAuthenticator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IngestionException.class)
    public ResponseEntity<Map<String, String>> handleIngestion(IngestionException e) {
        HttpStatus status = switch (e.getReason()) {
            case MALFORMED_MESSAGE, INVALID_DATE, INVALID_DECISION -> HttpStatus.BAD_REQUEST;
            case MISSING_REQUIRED_FIELD, ILLEGAL_IDENTIFIER -> HttpStatus.UNPROCESSABLE_ENTITY;
            case UNSUPPORTED_FORMAT -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case CONTRADICTORY_DATA, REVIEW_QUEUE_FULL, REVIEW_ALREADY_DECIDED -> HttpStatus.CONFLICT;
            case REVIEW_TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status).body(Map.of(
                "error", "REJECTED",
                "reason", e.getReason().name(),
                "detail", e.getMessage()));
    }

    @ExceptionHandler(PatientController.WebSecurityException.class)
    public ResponseEntity<Map<String, String>> handleSecurity(PatientController.WebSecurityException e) {
        ApiAuthenticator.AuthFailure failure = e.getFailure();
        HttpStatus status = failure == ApiAuthenticator.AuthFailure.FORBIDDEN_SCOPE
                ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status).body(Map.of(
                "error", failure.name(),
                "detail", e.getMessage()));
    }
}
