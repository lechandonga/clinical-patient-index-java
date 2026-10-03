package com.github.lechandonga.clinicalpatientindexjava.domain;

import java.util.List;

/**
 * 结构化的人口学冲突异常。除了与 {@link IngestionException} 一致的拒绝语义外，
 * 还携带登记人工复核所需的信息：冲突类型、涉及的两个主索引（未决时各自独立）、
 * 触发冲突的 canonical 标识符 key（明文 key 仅用于进程内计算，禁止出现在
 * 对外响应、待办记录与审计中）。
 */
public class ConflictException extends IngestionException {

    /**
     * 冲突类别。
     */
    public enum ConflictType {
        /** 出生日期冲突。 */
        BIRTH_DATE,
        /** 出生日期一致但姓氏冲突。 */
        FAMILY_NAME
    }

    private final ConflictType conflictType;
    private final String mpiA;
    private final String mpiB;
    private final String linkIdentifierKey;
    private final List<String> extraMpis;

    public ConflictException(ConflictType conflictType,
                             String mpiA,
                             String mpiB,
                             String linkIdentifierKey,
                             String message) {
        this(conflictType, mpiA, mpiB, linkIdentifierKey, message, List.of());
    }

    public ConflictException(ConflictType conflictType,
                             String mpiA,
                             String mpiB,
                             String linkIdentifierKey,
                             String message,
                             List<String> extraMpis) {
        super(RejectionReason.CONTRADICTORY_DATA, message);
        this.conflictType = conflictType;
        this.mpiA = mpiA;
        this.mpiB = mpiB;
        this.linkIdentifierKey = linkIdentifierKey;
        this.extraMpis = List.copyOf(extraMpis);
    }

    public ConflictType getConflictType() {
        return conflictType;
    }

    /**
     * 冲突涉及的主索引 id（确定性排序，便于去重）。
     */
    public List<String> getMpiIds() {
        List<String> ids = new java.util.ArrayList<>();
        ids.add(mpiA);
        if (mpiB != null) {
            ids.add(mpiB);
        }
        ids.addAll(extraMpis);
        ids.sort(String::compareTo);
        return ids.stream().distinct().toList();
    }

    public String getLinkIdentifierKey() {
        return linkIdentifierKey;
    }

    /**
     * 同一对患者 + 同一类冲突在未决期间的去重键。
     */
    public String dedupKey() {
        return String.join("|", getMpiIds()) + "#" + conflictType.name();
    }
}
