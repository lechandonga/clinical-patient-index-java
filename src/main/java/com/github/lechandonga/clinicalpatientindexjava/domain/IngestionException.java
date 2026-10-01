package com.github.lechandonga.clinicalpatientindexjava.domain;

/**
 * 可区分失败原因的接入异常。
 */
public class IngestionException extends RuntimeException {

    private final RejectionReason reason;

    public IngestionException(RejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public IngestionException(RejectionReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public RejectionReason getReason() {
        return reason;
    }

    /**
     * 明确区分的失败原因码。
     */
    public enum RejectionReason {
        /** 消息语法无法解析（段结构、JSON 语法、字符集非法等）。 */
        MALFORMED_MESSAGE,
        /** 不支持的消息格式或版本。 */
        UNSUPPORTED_FORMAT,
        /** 必填字段缺失（如患者姓名、至少一个标识符）。 */
        MISSING_REQUIRED_FIELD,
        /** 标识符不合法（空值、类型/系统不允许、字符非法）。 */
        ILLEGAL_IDENTIFIER,
        /** 时间/日期格式无法按规则补全或解析。 */
        INVALID_DATE,
        /** 同一消息内部互相矛盾且无法裁决的数据。 */
        CONTRADICTORY_DATA
    }
}
