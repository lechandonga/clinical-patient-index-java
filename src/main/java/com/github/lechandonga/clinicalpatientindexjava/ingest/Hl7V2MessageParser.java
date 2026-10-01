package com.github.lechandonga.clinicalpatientindexjava.ingest;

import com.github.lechandonga.clinicalpatientindexjava.domain.Gender;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException.RejectionReason;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HL7 v2.x 管道分隔 ADT^A04/A08/A01/A31 消息解析器。
 *
 * 段顺序容忍：MSH 必须出现且位于首个非空行，其余段（PID 等）允许乱序、重复；
 * 字段缺失不静默丢弃，无法满足必填要求时抛出带区分原因的 {@link IngestionException}。
 */
public class Hl7V2MessageParser implements MessageParser {

    private static final List<String> SUPPORTED_EVENTS = List.of("A01", "A04", "A08", "A31");

    @Override
    public boolean supports(String contentType, String body) {
        if (body == null) {
            return false;
        }
        String head = body.substring(0, Math.min(body.length(), 200));
        return head.startsWith("MSH|")
                || (contentType != null && (contentType.contains("hl7") || contentType.contains("er7")));
    }

    @Override
    public NormalizedPatient parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IngestionException(RejectionReason.MALFORMED_MESSAGE, "empty HL7 message");
        }
        validateUtf8(body);

        String[] lines = body.strip().split("\\r\\n|\\r|\\n");
        String firstNonEmpty = null;
        for (String line : lines) {
            if (!line.isBlank()) {
                firstNonEmpty = line.strip();
                break;
            }
        }
        if (firstNonEmpty == null || !firstNonEmpty.startsWith("MSH|")) {
            throw new IngestionException(RejectionReason.MALFORMED_MESSAGE,
                    "first segment must be MSH, got: " + safeHead(firstNonEmpty));
        }

        Map<String, String[]> segments = new LinkedHashMap<>();
        int mshCount = 0;
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            String segName = line.length() >= 3 ? line.substring(0, 3) : line;
            if ("MSH".equals(segName)) {
                mshCount++;
            }
            segments.put(segName, splitMshAware(line));
        }
        if (mshCount != 1) {
            throw new IngestionException(RejectionReason.MALFORMED_MESSAGE,
                    "exactly one MSH segment required, found " + mshCount);
        }

        String[] msh = segments.get("MSH");
        String messageType = getField(msh, 9);
        String version = getField(msh, 12);
        if (version == null || !version.startsWith("2.")) {
            throw new IngestionException(RejectionReason.UNSUPPORTED_FORMAT,
                    "unsupported HL7 version: '" + version + "' (only 2.x supported)");
        }
        String event = extractEvent(messageType);
        if (event == null || !SUPPORTED_EVENTS.contains(event)) {
            throw new IngestionException(RejectionReason.UNSUPPORTED_FORMAT,
                    "unsupported HL7 event/type: '" + messageType + "'");
        }

        String[] pid = segments.get("PID");
        if (pid == null) {
            throw new IngestionException(RejectionReason.MISSING_REQUIRED_FIELD,
                    "PID segment is required for patient identity message");
        }

        NormalizedPatient patient = new NormalizedPatient();

        String externalId = component(getField(pid, 2), 0);
        if (externalId != null) {
            addIdentifier(patient, "EXTERNAL", externalId);
        }
        String idList = getField(pid, 3);
        if (idList != null) {
            for (String repeat : idList.split("~")) {
                String value = component(repeat, 0);
                String assigningAuthority = component(repeat, 3);
                String type = component(repeat, 4);
                String system = resolveSystem(assigningAuthority, type);
                addIdentifier(patient, system, value);
            }
        }
        if (patient.getIdentifiers().isEmpty()) {
            throw new IngestionException(RejectionReason.MISSING_REQUIRED_FIELD,
                    "at least one patient identifier (PID-3) is required");
        }

        String name = getField(pid, 5);
        if (name == null || (component(name, 0) == null && component(name, 1) == null)) {
            throw new IngestionException(RejectionReason.MISSING_REQUIRED_FIELD,
                    "patient legal name (PID-5) is required");
        }
        patient.setFamilyName(emptyToNull(component(name, 0)));
        patient.setGivenName(emptyToNull(component(name, 1)));

        String dob = getField(pid, 7);
        if (dob == null || dob.isBlank()) {
            throw new IngestionException(RejectionReason.MISSING_REQUIRED_FIELD,
                    "patient birth date (PID-7) is required");
        }
        DateNormalizer.Result dateResult = DateNormalizer.normalize(dob);
        patient.setBirthDate(dateResult.isoDate());
        if (dateResult.note() != null) {
            patient.getNotes().add(dateResult.note());
        }

        patient.setGender(Gender.normalize(getField(pid, 8)));
        if (getField(pid, 8) != null && patient.getGender() == Gender.UNKNOWN) {
            patient.getNotes().add("unmapped administrative sex '" + getField(pid, 8) + "' -> UNKNOWN");
        }
        return patient;
    }

    @Override
    public String formatVersion() {
        return "HL7 v2.x (pipe-delimited ER7)";
    }

    private static void addIdentifier(NormalizedPatient patient, String system, String value) {
        if (value == null || value.isBlank()) {
            throw new IngestionException(RejectionReason.ILLEGAL_IDENTIFIER,
                    "identifier value is empty for system '" + system + "'");
        }
        String trimmed = value.trim();
        if (!trimmed.matches("[\\p{L}\\p{N}\\-_:#. /]+")) {
            throw new IngestionException(RejectionReason.ILLEGAL_IDENTIFIER,
                    "identifier contains illegal characters: '" + abbreviate(trimmed) + "'");
        }
        patient.getIdentifiers().add(new PatientIdentifier(system, trimmed));
    }

    private static String resolveSystem(String assigningAuthority, String type) {
        if (assigningAuthority != null && !assigningAuthority.isBlank()) {
            return PatientIdentifier.normalizeSystem(assigningAuthority);
        }
        if ("MR".equalsIgnoreCase(type) || "MRN".equalsIgnoreCase(type)) {
            return "MR";
        }
        return "DEFAULT";
    }

    /**
     * MSH 段特殊：MSH 后紧跟分隔符本身，因此字段位序需偏移处理。
     */
    private static String[] splitMshAware(String line) {
        if (line.startsWith("MSH")) {
            char fieldSep = line.length() > 3 ? line.charAt(3) : '|';
            String[] parts = line.split(java.util.regex.Pattern.quote(String.valueOf(fieldSep)), -1);
            String[] aligned = new String[parts.length + 1];
            aligned[0] = "MSH";
            aligned[1] = String.valueOf(fieldSep);
            System.arraycopy(parts, 1, aligned, 2, parts.length - 1);
            return aligned;
        }
        return line.split("\\|", -1);
    }

    private static String getField(String[] fields, int oneBased) {
        if (fields == null || oneBased >= fields.length) {
            return null;
        }
        String v = fields[oneBased];
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String component(String field, int index) {
        if (field == null) {
            return null;
        }
        String[] components = field.split("\\^", -1);
        if (index >= components.length) {
            return null;
        }
        String c = components[index].trim();
        return c.isEmpty() ? null : c;
    }

    private static String extractEvent(String messageType) {
        if (messageType == null) {
            return null;
        }
        String[] parts = messageType.split("\\^");
        return parts.length >= 2 ? parts[1].trim() : null;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String safeHead(String s) {
        return s == null ? "null" : abbreviate(s);
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }

    private static void validateUtf8(String body) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
        } catch (CharacterCodingException e) {
            throw new IngestionException(RejectionReason.MALFORMED_MESSAGE,
                    "message contains malformed UTF-8 sequences", e);
        }
    }
}
