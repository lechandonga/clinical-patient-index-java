package com.github.lechandonga.clinicalpatientindexjava.web.dto;

/**
 * @param errorCode   可区分的失败原因
 * @param message     面向调用方的解释（不含敏感明文）
 * @param inputDigest 输入摘要，便于与审计/测试日志对账
 */
public record ErrorResponse(String errorCode, String message, String inputDigest) {
}
