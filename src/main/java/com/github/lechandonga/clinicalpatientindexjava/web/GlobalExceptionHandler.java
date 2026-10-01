package com.github.lechandonga.clinicalpatientindexjava.web;

import com.github.lechandonga.clinicalpatientindexjava.deid.DeIdentificationService;
import com.github.lechandonga.clinicalpatientindexjava.ingest.ErrorCode;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IngestException;
import com.github.lechandonga.clinicalpatientindexjava.web.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IngestException.class)
    public ResponseEntity<ErrorResponse> handle(IngestException exception, HttpServletRequest request) {
        return ResponseEntity
                .status(statusOf(exception.getCode()))
                .body(new ErrorResponse(exception.getCode().name(), exception.getMessage(),
                        DeIdentificationService.fingerprint(
                                request.getRequestURI() + "|" + safeBody(request))));
    }

    private static String safeBody(HttpServletRequest request) {
        Object digest = request.getAttribute("cpi.inputDigest");
        return digest == null ? "" : digest.toString();
    }

    private static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN_ROLE, FORBIDDEN_SCOPE -> HttpStatus.FORBIDDEN;
            case PATIENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE_CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
