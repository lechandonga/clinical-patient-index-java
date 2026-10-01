package com.github.lechandonga.clinicalpatientindexjava.ingest.hl7;

import com.github.lechandonga.clinicalpatientindexjava.domain.Gender;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.ingest.ErrorCode;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IdentifierNormalizer;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IngestException;
import com.github.lechandonga.clinicalpatientindexjava.ingest.PatientParser;
import com.github.lechandonga.clinicalpatientindexjava.ingest.TimeNormalizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HL7 v2.x（管道分隔）ADT 消息解析器。
 * <p>支持的消息：ADT^A01 / A04 / A08 / A28 / A31 / A40，版本 2.1–2.8。
 * 分段允许乱序；MSH 必须唯一且为首段，PID 必须唯一。
 */
@Component
public class Hl7v2PatientParser implements PatientParser {

    private static final Set<String> SUPPORTED_EVENTS = Set.of("A01", "A04", "A08", "A28", "A31", "A40");
    private static final Set<String> SUPPORTED_VERSIONS = Set.of("2.1", "2.2", "2.3", "2.3.1", "2.4", "2.5", "2.5.1", "2.6", "2.7", "2.8");

    private final IdentifierNormalizer identifierNormalizer;

    public Hl7v2PatientParser(IdentifierNormalizer identifierNormalizer) {
        this.identifierNormalizer = identifierNormalizer;
    }

    @Override
    public boolean supports(String raw) {
        String stripped = stripBom(raw);
        return stripped.startsWith("MSH");
    }

    @Override
    public String formatName() {
        return "HL7v2";
    }

    @Override
    public NormalizedPatient parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IngestException(ErrorCode.FORMAT_ERROR, "HL7 消息为空");
        }
        String content = stripBom(raw).replace("\r\n", "\r").replace('\n', '\r').trim();
        if (!content.startsWith("MSH")) {
            throw new IngestException(ErrorCode.FORMAT_ERROR, "HL7 消息必须以 MSH 段开头");
        }
        String[] lines = content.split("\r");
        Map<String, String[]> segments = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String name = line.length() >= 3 ? line.substring(0, 3) : line;
            if (segments.put(name, splitSegment(line)) != null) {
                throw new IngestException(ErrorCode.FORMAT_ERROR, "HL7 段重复出现: " + name);
            }
        }

        String[] msh = segments.get("MSH");
        if (msh == null || msh.length < 13) {
            throw new IngestException(ErrorCode.FORMAT_ERROR, "MSH 段缺失或字段不足（至少需要 MSH-12 版本号）");
        }
        String fieldSeparator = String.valueOf(content.charAt(3));
        String encodingCharacters = msh[2];
        if (encodingCharacters.length() < 2) {
            throw new IngestException(ErrorCode.FORMAT_ERROR, "MSH-2 编码字符非法");
        }
        char componentSeparator = encodingCharacters.charAt(0);
        String messageTypeField = component(msh[9], componentSeparator, 0);
        String triggerEvent = component(msh[9], componentSeparator, 1);
        if (messageTypeField == null || (!"ADT".equals(messageTypeField))) {
            throw new IngestException(ErrorCode.FORMAT_ERROR,
                    "仅支持 ADT 消息，实际 MSH-9=" + safe(msh[9]));
        }
        if (triggerEvent == null || !SUPPORTED_EVENTS.contains(triggerEvent.toUpperCase())) {
            throw new IngestException(ErrorCode.FORMAT_ERROR,
                    "不支持的 ADT 触发事件: " + safe(triggerEvent));
        }
        String version = msh[12];
        if (!SUPPORTED_VERSIONS.contains(version)) {
            throw new IngestException(ErrorCode.UNSUPPORTED_VERSION,
                    "不支持的 HL7 版本: " + safe(version) + "，支持 2.1–2.8");
        }

        String[] pid = segments.get("PID");
        if (pid == null) {
            throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD, "缺少必填 PID 段");
        }
        if (pid.length < 8) {
            throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD,
                    "PID 段字段不足，至少需要 PID-3/PID-5/PID-7/PID-8");
        }

        NormalizedPatient patient = new NormalizedPatient();
        parseIdentifiers(pid[3], componentSeparator, msh, patient);
        parseName(pid[5], componentSeparator, patient);
        patient.setBirthDate(TimeNormalizer.normalizeBirthDate(emptyToNull(pid[7])));
        patient.setGender(parseGender(pid[8]));
        if (pid.length > 13) {
            String phone = component(pid[13], componentSeparator, 0);
            if (phone != null && !phone.isBlank()) {
                patient.setPhone(phone.trim());
            }
        }
        validateDemographics(patient);
        return patient;
    }

    private void parseIdentifiers(String pid3, char componentSeparator, String[] msh, NormalizedPatient patient) {
        if (pid3 == null || pid3.isBlank()) {
            throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD, "PID-3 患者标识符列表为空");
        }
        char repetitionSeparator = msh[2].length() >= 2 ? msh[2].charAt(1) : '~';
        String[] repetitions = pid3.split(java.util.regex.Pattern.quote(String.valueOf(repetitionSeparator)));
        String defaultSystem = msh.length > 4 && !msh[4].isBlank() ? msh[4] : "UNKNOWN";
        List<String> keys = new ArrayList<>();
        for (String repetition : repetitions) {
            if (repetition.isBlank()) {
                continue;
            }
            String value = component(repetition, componentSeparator, 0);
            String system = component(repetition, componentSeparator, 3);
            String type = component(repetition, componentSeparator, 4);
            String assigningAuthority = (system == null || system.isBlank()) ? defaultSystem : system;
            if (value == null || value.isBlank()) {
                throw new IngestException(ErrorCode.IDENTIFIER_INVALID, "PID-3 存在空标识符");
            }
            var normalized = identifierNormalizer.normalize(assigningAuthority, value, type);
            if (keys.add(normalized.canonicalKey())) {
                patient.addIdentifier(normalized);
            }
        }
        if (patient.getIdentifiers().isEmpty()) {
            throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD, "PID-3 未提供任何有效患者标识符");
        }
    }

    private void parseName(String pid5, char componentSeparator, NormalizedPatient patient) {
        if (pid5 == null || pid5.isBlank()) {
            throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD, "PID-5 患者姓名为空");
        }
        String family = component(pid5, componentSeparator, 0);
        String given = component(pid5, componentSeparator, 1);
        if ((family == null || family.isBlank()) && (given == null || given.isBlank())) {
            throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD, "PID-5 姓氏与名字均为空");
        }
        patient.setFamilyName(family == null ? null : family.trim());
        patient.setGivenName(given == null ? null : given.trim());
    }

    private static Gender parseGender(String code) {
        if (code == null || code.isBlank()) {
            return Gender.UNK;
        }
        return switch (code.trim().toUpperCase()) {
            case "M" -> Gender.M;
            case "F" -> Gender.F;
            case "U" -> Gender.U;
            case "A", "N", "O" -> Gender.UNK;
            default -> throw new IngestException(ErrorCode.FORMAT_ERROR, "PID-8 性别代码无法解释: " + safe(code));
        };
    }

    private void validateDemographics(NormalizedPatient patient) {
        if (patient.getBirthDate() == null) {
            throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD, "PID-7 出生日期缺失");
        }
        if (patient.getFamilyName() == null && patient.getGivenName() == null) {
            throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD, "PID-5 患者姓名缺失");
        }
    }

    /** MSH 行特殊：分隔符本身是 MSH-1，编码字符是 MSH-2；其余段按常规管道切分。 */
    private static String[] splitSegment(String line) {
        String separator = String.valueOf(line.charAt(3));
        if (!line.startsWith("MSH")) {
            return line.split(java.util.regex.Pattern.quote(separator), -1);
        }
        String[] raw = line.split(java.util.regex.Pattern.quote(separator), -1);
        String[] fields = new String[raw.length + 1];
        fields[0] = "MSH";
        fields[1] = separator;
        System.arraycopy(raw, 1, fields, 2, raw.length - 1);
        return fields;
    }

    private static String component(String field, char componentSeparator, int index) {
        if (field == null) {
            return null;
        }
        String[] parts = field.split(java.util.regex.Pattern.quote(String.valueOf(componentSeparator)), -1);
        return index < parts.length ? parts[index] : null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String safe(String value) {
        if (value == null) {
            return "<null>";
        }
        return value.length() > 24 ? value.substring(0, 24) + "..." : value;
    }

    private static String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }
}
