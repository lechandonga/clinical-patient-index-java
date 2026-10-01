package com.github.lechandonga.clinicalpatientindexjava.ingest.fhir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.clinicalpatientindexjava.domain.Gender;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.ingest.ErrorCode;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IdentifierNormalizer;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IngestException;
import com.github.lechandonga.clinicalpatientindexjava.ingest.PatientParser;
import com.github.lechandonga.clinicalpatientindexjava.ingest.TimeNormalizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * FHIR R4 风格的 Patient 资源 JSON 解析器。
 * <p>支持字段：resourceType、identifier(system/value/type.coding.code)、
 * name(family/given)、birthDate、gender、telecom(phone)。
 * 资源类型必须是 Patient；仅支持 R4。
 */
@Component
public class FhirPatientParser implements PatientParser {

    private final ObjectMapper objectMapper;
    private final IdentifierNormalizer identifierNormalizer;

    public FhirPatientParser(ObjectMapper objectMapper, IdentifierNormalizer identifierNormalizer) {
        this.objectMapper = objectMapper;
        this.identifierNormalizer = identifierNormalizer;
    }

    @Override
    public boolean supports(String raw) {
        String trimmed = raw == null ? "" : raw.stripLeading();
        if (!trimmed.startsWith("{")) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            return node != null && node.has("resourceType");
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    @Override
    public String formatName() {
        return "FHIR-R4";
    }

    @Override
    public NormalizedPatient parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IngestException(ErrorCode.FORMAT_ERROR, "FHIR 资源为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(raw.strip());
        } catch (JsonProcessingException e) {
            throw new IngestException(ErrorCode.FORMAT_ERROR, "FHIR JSON 语法错误: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IngestException(ErrorCode.FORMAT_ERROR, "FHIR 资源根节点必须是 JSON 对象");
        }
        String resourceType = text(root, "resourceType");
        if (resourceType == null) {
            throw new IngestException(ErrorCode.FORMAT_ERROR, "缺少 resourceType 字段");
        }
        if (!"Patient".equals(resourceType)) {
            throw new IngestException(ErrorCode.FORMAT_ERROR,
                    "仅支持 Patient 资源，实际 resourceType=" + resourceType);
        }
        String fhirVersion = text(root, "meta.fhirVersion");
        if (fhirVersion != null && !fhirVersion.startsWith("4.")) {
            throw new IngestException(ErrorCode.UNSUPPORTED_VERSION,
                    "仅支持 FHIR R4 (4.x)，实际声明版本=" + fhirVersion);
        }

        NormalizedPatient patient = new NormalizedPatient();
        parseIdentifiers(root.get("identifier"), patient);
        parseName(root.get("name"), patient);
        String birthDate = text(root, "birthDate");
        patient.setBirthDate(TimeNormalizer.normalizeBirthDate(birthDate));
        patient.setGender(parseGender(text(root, "gender")));
        parsePhone(root.get("telecom"), patient);
        return patient;
    }

    private void parseIdentifiers(JsonNode identifiers, NormalizedPatient patient) {
        if (identifiers == null || !identifiers.isArray() || identifiers.isEmpty()) {
            throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD, "identifier 为必填且至少包含一个标识符");
        }
        List<String> keys = new ArrayList<>();
        for (JsonNode identifier : identifiers) {
            String value = text(identifier, "value");
            if (value == null || value.isBlank()) {
                throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD, "identifier.value 缺失");
            }
            String system = text(identifier, "system");
            if (system == null || system.isBlank()) {
                throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD,
                        "identifier.system 缺失，无法确定标识符来源");
            }
            String typeCode = text(identifier, "type.coding.0.code");
            var normalized = identifierNormalizer.normalize(system, value, typeCode);
            if (keys.add(normalized.canonicalKey())) {
                patient.addIdentifier(normalized);
            }
        }
    }

    private void parseName(JsonNode names, NormalizedPatient patient) {
        if (names == null || !names.isArray() || names.isEmpty()) {
            throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD, "name 为必填");
        }
        JsonNode official = null;
        for (JsonNode name : names) {
            if ("official".equals(text(name, "use"))) {
                official = name;
                break;
            }
        }
        if (official == null) {
            official = names.get(0);
        }
        String family = text(official, "family");
        String given = official.hasNonNull("given") && official.get("given").isArray()
                && !official.get("given").isEmpty() ? official.get("given").get(0).asText() : null;
        if ((family == null || family.isBlank()) && (given == null || given.isBlank())) {
            throw new IngestException(ErrorCode.MISSING_REQUIRED_FIELD, "name.family 与 name.given 均为空");
        }
        patient.setFamilyName(family == null || family.isBlank() ? null : family.trim());
        patient.setGivenName(given == null || given.isBlank() ? null : given.trim());
    }

    private static Gender parseGender(String gender) {
        if (gender == null || gender.isBlank()) {
            return Gender.UNK;
        }
        return switch (gender.trim().toLowerCase()) {
            case "male" -> Gender.M;
            case "female" -> Gender.F;
            case "unknown" -> Gender.U;
            case "other" -> Gender.UNK;
            default -> throw new IngestException(ErrorCode.FORMAT_ERROR, "gender 取值无法解释: " + gender);
        };
    }

    private void parsePhone(JsonNode telecoms, NormalizedPatient patient) {
        if (telecoms == null || !telecoms.isArray()) {
            return;
        }
        for (JsonNode telecom : telecoms) {
            String system = text(telecom, "system");
            String value = text(telecom, "value");
            if ("phone".equals(system) && value != null && !value.isBlank()) {
                patient.setPhone(value.trim());
                return;
            }
        }
    }

    private static String text(JsonNode node, String path) {
        JsonNode current = node;
        for (String part : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            if (part.matches("\\d+")) {
                int index = Integer.parseInt(part);
                current = current.isArray() && index < current.size() ? current.get(index) : null;
            } else {
                current = current.get(part);
            }
        }
        return current != null && current.isValueNode() && !current.isNull() ? current.asText() : null;
    }
}
