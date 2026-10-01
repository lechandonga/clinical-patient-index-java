package com.github.lechandonga.clinicalpatientindexjava.ingest;

import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;

/**
 * 输入消息解析器：将原始消息归一为 {@link NormalizedPatient}。
 */
public interface MessageParser {

    boolean supports(String contentType, String body);

    NormalizedPatient parse(String body);

    /** 格式与版本描述，用于审计。 */
    String formatVersion();
}
