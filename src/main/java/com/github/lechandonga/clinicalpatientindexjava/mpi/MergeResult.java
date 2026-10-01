package com.github.lechandonga.clinicalpatientindexjava.mpi;

import java.util.List;

/**
 * @param masterId     主索引 ID（稳定伪名）
 * @param created      是否首次建立主索引
 * @param mergedGroups 本次归并的既有主索引数量（用于并发传递归并观测）
 * @param warnings     可解释的人口学冲突/补全说明
 */
public record MergeResult(String masterId, boolean created, int mergedGroups, List<String> warnings) {
}
