package com.github.lechandonga.clinicalpatientindexjava.ingest;

/**
 * 带明确失败原因的业务异常。
 */
public class IngestException extends RuntimeException {

    private final ErrorCode code;

    public IngestException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
