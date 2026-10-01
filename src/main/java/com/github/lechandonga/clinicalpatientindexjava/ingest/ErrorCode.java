package com.github.lechandonga.clinicalpatientindexjava.ingest;

/**
 * 可区分的失败原因，错误码会原样返回给调用方并写入审计日志。
 */
public enum ErrorCode {
    FORMAT_ERROR,
    MISSING_REQUIRED_FIELD,
    IDENTIFIER_INVALID,
    UNSUPPORTED_VERSION,
    TIME_FORMAT_ERROR,
    DUPLICATE_CONFLICT,
    PATIENT_NOT_FOUND,
    UNAUTHENTICATED,
    FORBIDDEN_SCOPE,
    FORBIDDEN_ROLE
}
