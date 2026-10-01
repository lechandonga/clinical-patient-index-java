package com.github.lechandonga.clinicalpatientindexjava.web.dto;

import java.util.List;

/**
 * 去标识化患者视图：不含姓名、证件号、联系方式等直接标识。
 *
 * @param pseudonym        患者稳定伪名（同一患者跨输出一致）
 * @param birthYear        出生年份（泛化，而非完整生日）
 * @param gender           归一性别
 * @param identifierRefs   来源标识符的非可逆引用（伪名化，不含原始值）
 * @param sourceSystems    数据来源系统
 */
public record DeidentifiedPatientView(String pseudonym,
                                      String birthYear,
                                      String gender,
                                      List<String> identifierRefs,
                                      List<String> sourceSystems) {
}
