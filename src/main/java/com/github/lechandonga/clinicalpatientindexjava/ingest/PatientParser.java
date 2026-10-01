package com.github.lechandonga.clinicalpatientindexjava.ingest;

import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;

/** 输入格式解析器：负责把外部消息解析并归一为 {@link NormalizedPatient}。 */
public interface PatientParser {

    /** 是否能处理该输入（嗅探阶段，不改变异常语义）。 */
    boolean supports(String raw);

    /** 解析输入，格式/字段/标识符问题必须抛出带 {@link ErrorCode} 的 {@link IngestException}。 */
    NormalizedPatient parse(String raw);

    /** 格式名称，用于审计与日志。 */
    String formatName();
}
