package com.github.lechandonga.clinicalpatientindexjava.ingest;

import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 把不同来源的时间/日期表示统一为 ISO 本地日期 {@code yyyy-MM-dd}。
 * 仅做显式规则补全，绝不猜测；非法历法日期直接拒绝。
 */
public final class TimeNormalizer {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private TimeNormalizer() {
    }

    public static String normalizeBirthDate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD, "出生日期为必填字段");
        }
        String value = stripBom(raw.trim());

        LocalDate parsed;
        if (value.matches("\\d{8}")) {
            parsed = parseCompact(value, 0);
        } else if (value.matches("\\d{12}")) {
            parsed = parseCompact(value, 0);
        } else if (value.matches("\\d{14}(\\.\\d+)?")) {
            parsed = parseCompact(value, 0);
        } else if (value.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            String datePart = value.length() >= 10 ? value.substring(0, 10) : value;
            try {
                parsed = LocalDate.parse(datePart, ISO);
            } catch (DateTimeParseException e) {
                throw timeError(value);
            }
        } else if (value.matches("\\d{8}[+-]\\d{4}")) {
            parsed = parseCompact(value, 0);
        } else {
            throw timeError(value);
        }
        if (parsed.isAfter(LocalDate.now())) {
            throw new IngestException(ErrorCode.TIME_FORMAT_ERROR,
                    "出生日期晚于当前日期，按异常时间拒绝: " + mask(value));
        }
        return parsed.format(ISO);
    }

    private static LocalDate parseCompact(String value, int offset) {
        try {
            int year = Integer.parseInt(value.substring(offset, offset + 4));
            int month = Integer.parseInt(value.substring(offset + 4, offset + 6));
            int day = Integer.parseInt(value.substring(offset + 6, offset + 8));
            return LocalDate.of(year, month, day);
        } catch (DateTimeException | NumberFormatException e) {
            throw timeError(value);
        }
    }

    private static IngestException timeError(String value) {
        return new IngestException(ErrorCode.TIME_FORMAT_ERROR,
                "无法解释的日期格式或非法历法日期: " + mask(value));
    }

    private static String stripBom(String value) {
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private static String mask(String value) {
        return value.length() > 32 ? value.substring(0, 32) + "..." : value;
    }
}
