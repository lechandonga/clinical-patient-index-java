package com.github.lechandonga.clinicalpatientindexjava.web.dto;

import java.util.List;

/**
 * 接入结果。
 *
 * @param masterId    稳定主索引伪名
 * @param created     是否新建主索引
 * @param mergedGroups 本次归并的既有索引组数量
 * @param warnings    可解释的补全/冲突说明
 * @param format      识别出的输入格式
 */
public record IngestResponse(String masterId, boolean created, int mergedGroups,
                             List<String> warnings, String format) {
}
