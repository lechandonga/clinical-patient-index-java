package com.github.lechandonga.clinicalpatientindexjava.ingest;

import java.time.LocalDate;
import java.time.DateTimeException;

/**
 * 日期归一：统一输出 ISO yyyy-MM-dd。
 * 支持 HL7 (yyyyMMdd[HHmm[ss]]) 与 FHIR (yyyy-MM-dd)；
 * 部分日期按可解释规则补全（yyyy -> yyyy-01-01，yyyy-MM -> yyyy-MM-01）。
 */
public final class DateNormalizer {

    private DateNormalizer() {
    }

    /**
 * @return 归一结果，含补全日志说明
 */
    public static Result normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        String note = null;
        String digits = s.replaceAll("[^0-9]", "");
        try {
            if (s.matches("\\d{8}.*")) {
                LocalDate date = LocalDate.of(
                        Integer.parseInt(s.substring(0, 4)),
                        Integer.parseInt(s.substring(4, 6)),
                        Integer.parseInt(s.substring(6, 8)));
                return new Result(date.toString(), note);
            }
            if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                return new Result(LocalDate.parse(s.substring(0, 10)).toString(), note);
            }
            if (s.matches("\\d{4}-\\d{2}")) {
                String iso = s + "-01";
                LocalDate.parse(iso);
                return new Result(iso, "partial date '" + s + "' completed with day=01");
            }
            if (s.matches("\\d{4}")) {
                String iso = s + "-01-01";
                return new Result(iso, "partial date '" + s + "' completed with month=01,day=01");
            }
            if (digits.length() >= 8) {
                LocalDate date = LocalDate.of(
                        Integer.parseInt(digits.substring(0, 4)),
                        Integer.parseInt(digits.substring(4, 6)),
                        Integer.parseInt(digits.substring(6, 8)));
                return new Result(date.toString(), "date '" + s + "' parsed leniently as yyyyMMdd");
            }
        } catch (DateTimeException | NumberFormatException | IndexOutOfBoundsException e) {
            throw new com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException(
                    com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException.RejectionReason.INVALID_DATE,
                    "invalid date value: '" + raw + "'", e);
        }
        throw new com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException(
                com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException.RejectionReason.INVALID_DATE,
                "unrecognized date format: '" + raw + "'");
    }

    public record Result(String isoDate, String note) {
    }
}
