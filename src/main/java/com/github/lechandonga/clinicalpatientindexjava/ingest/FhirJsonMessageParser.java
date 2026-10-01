package com.github.lechandonga.clinicalpatientindexjava.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.clinicalpatientindexjava.domain.Gender;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException.RejectionReason;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;

/**
 * FHIR R4/R5 风格 Patient 资源（JSON）解析器。
 * 兼容 R4 字段结构；R5 的增量差异不影响 Patient 基础身份字段。
 */
public class FhirJsonMessageParser implements MessageParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(String contentType, String body) {
        if (contentType != null && (contentType.contains("fhir") || contentType.contains("json"))) {
            return body != null;
        }
        if (body == null) {
            return false;
        }
        String head = body.substring(0, Math.min(body.length(), 200));
        return head.matches("(?s)\\s*\\{.*") && head.contains("\"resourceType\"");
    }

    @Override
    public NormalizedPatient parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IngestionException(RejectionReason.MALFORMED_MESSAGE, "empty FHIR payload");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IngestionException(RejectionReason.MALFORMED_MESSAGE,
                    "invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IngestionException(RejectionReason.MALFORMED_MESSAGE, "payload must be a JSON object");
        }
        JsonNode resourceType = root.get("resourceType");
        if (resourceType == null || !"Patient".equals(resourceType.asText())) {
            throw new IngestionException(RejectionReason.UNSUPPORTED_FORMAT,
                    "only FHIR Patient resources are supported, got resourceType="
                            + (resourceType == null ? "null" : "'" + resourceType.asText() + "'"));
        }

        String fhirVersion = root.path("meta").path("versionId").asText(null);

        NormalizedPatient patient = new NormalizedPatient();
        JsonNode identifiers = root.get("identifier");
        if (identifiers == null || !identifiers.isArray() || identifiers.isEmpty()) {
            throw new IngestionException(RejectionReason.MISSING_REQUIRED_FIELD,
                    "at least one Patient.identifier is required");
        }
        for (JsonNode id : identifiers) {
            JsonNode valueNode = id.get("value");
            String value = valueNode == null ? null : valueNode.asText(null);
            if (value == null || value.isBlank()) {
                throw new IngestionException(RejectionReason.ILLEGAL_IDENTIFIER,
                        "identifier.value is empty");
            }
            value = value.trim();
            if (!value.matches("[\\p{L}\\p{N}\\-_:#. /]+")) {
                throw new IngestionException(RejectionReason.ILLEGAL_IDENTIFIER,
                        "identifier.value contains illegal characters: '" + abbreviate(value) + "'");
            }
            String system = id.path("system").asText(null);
            JsonNode coding = id.path("type").path("coding");
            String typeCode = coding.isArray() && !coding.isEmpty()
                    ? coding.get(0).path("code").asText(null) : null;
            String resolved = system != null && !system.isBlank()
                    ? system.substring(system.lastIndexOf('/') + 1)
                    : (typeCode != null ? typeCode : "DEFAULT");
            patient.getIdentifiers().add(new PatientIdentifier(
                    PatientIdentifier.normalizeSystem(resolved), value));
        }

        JsonNode names = root.get("name");
        String family = null;
        String given = null;
        if (names != null && names.isArray()) {
            for (JsonNode name : names) {
                boolean useOfficial = !name.has("use")
                        || "official".equals(name.path("use").asText())
                        || "usual".equals(name.path("use").asText());
                String f = name.path("family").asText(null);
                JsonNode givenArray = name.get("given");
                String g = givenArray != null && givenArray.isArray() && !givenArray.isEmpty()
                        ? givenArray.get(0).asText(null) : null;
                if (family == null && f != null) {
                    family = f;
                }
                if (given == null && g != null) {
                    given = g;
                }
                if (useOfficial && (f != null || g != null)) {
                    family = f != null ? f : family;
                    given = g != null ? g : given;
                    break;
                }
            }
        }
        if ((family == null || family.isBlank()) && (given == null || given.isBlank())) {
            throw new IngestionException(RejectionReason.MISSING_REQUIRED_FIELD,
                    "Patient.name with family or given is required");
        }
        patient.setFamilyName(family);
        patient.setGivenName(given);

        String birthDate = root.path("birthDate").asText(null);
        if (birthDate == null || birthDate.isBlank()) {
            throw new IngestionException(RejectionReason.MISSING_REQUIRED_FIELD,
                    "Patient.birthDate is required");
        }
        DateNormalizer.Result dateResult = DateNormalizer.normalize(birthDate);
        patient.setBirthDate(dateResult.isoDate());
        if (dateResult.note() != null) {
            patient.getNotes().add(dateResult.note());
        }

        String genderRaw = root.path("gender").asText(null);
        patient.setGender(Gender.normalize(genderRaw));
        if (genderRaw != null && patient.getGender() == Gender.UNKNOWN) {
            patient.getNotes().add("unmapped gender '" + genderRaw + "' -> UNKNOWN");
        }
        if (fhirVersion != null) {
            patient.getNotes().add("fhir meta.versionId=" + fhirVersion);
        }
        return patient;
    }

    @Override
    public String formatVersion() {
        return "FHIR R4/R5 Patient (JSON)";
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }
}
