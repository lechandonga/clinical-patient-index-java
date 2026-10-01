package com.github.lechandonga.clinicalpatientindexjava.tests.parser;

import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.ingest.FhirJsonMessageParser;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FhirJsonMessageParserTest {

    private static final Logger log = LoggerFactory.getLogger(FhirJsonMessageParserTest.class);

    private final FhirJsonMessageParser parser = new FhirJsonMessageParser();

    private static final String VALID = """
            {
              "resourceType": "Patient",
              "identifier": [
                {"system": "http://hospital-a.example/fhir/mrn", "value": "mrn-1001"},
                {"system": "http://hospital-b.example/fhir/mrn", "value": "MRN:1002"}
              ],
              "name": [{"use": "official", "family": "Zhang", "given": ["San"]}],
              "gender": "male",
              "birthDate": "1990-06"
            }
            """;

    @Test
    void parsesFhirR4AndCompletesPartialDate() {
        log.info("[TEST INPUT] FHIR valid with partial date");
        NormalizedPatient patient = parser.parse(VALID);
        log.info("[TEST BASIS] identifiers={}, birthDate={}, notes={}",
                patient.getIdentifiers(), patient.getBirthDate(), patient.getNotes());
        assertEquals(2, patient.getIdentifiers().size());
        assertEquals("1990-06-01", patient.getBirthDate());
        assertTrue(patient.getNotes().stream().anyMatch(n -> n.contains("completed")));
    }

    @Test
    void rejectsMalformedJsonWithDistinctReason() {
        String broken = "{\"resourceType\": \"Patient\", broken";
        log.info("[TEST INPUT] FHIR broken JSON: {}", broken);
        IngestionException ex = assertThrows(IngestionException.class, () -> parser.parse(broken));
        log.info("[TEST BASIS] reason={} detail={}", ex.getReason(), ex.getMessage());
        assertEquals(IngestionException.RejectionReason.MALFORMED_MESSAGE, ex.getReason());
    }

    @Test
    void rejectsWrongResourceTypeAndMissingFields() {
        String observation = "{\"resourceType\":\"Observation\"}";
        log.info("[TEST INPUT] FHIR resourceType=Observation");
        IngestionException typeEx = assertThrows(IngestionException.class, () -> parser.parse(observation));
        log.info("[TEST BASIS] reason={}", typeEx.getReason());
        assertEquals(IngestionException.RejectionReason.UNSUPPORTED_FORMAT, typeEx.getReason());

        String noIdentifier = "{\"resourceType\":\"Patient\","
                + "\"name\":[{\"family\":\"Zhang\"}],\"birthDate\":\"1990-06-15\"}";
        IngestionException idEx = assertThrows(IngestionException.class, () -> parser.parse(noIdentifier));
        log.info("[TEST INPUT] FHIR missing identifier -> [TEST BASIS] reason={}", idEx.getReason());
        assertEquals(IngestionException.RejectionReason.MISSING_REQUIRED_FIELD, idEx.getReason());

        String illegalValue = "{\"resourceType\":\"Patient\","
                + "\"identifier\":[{\"value\":\"bad@id!\"}],"
                + "\"name\":[{\"family\":\"Zhang\"}],\"birthDate\":\"1990-06-15\"}";
        IngestionException illegalEx =
                assertThrows(IngestionException.class, () -> parser.parse(illegalValue));
        log.info("[TEST INPUT] FHIR illegal identifier -> [TEST BASIS] reason={}", illegalEx.getReason());
        assertEquals(IngestionException.RejectionReason.ILLEGAL_IDENTIFIER, illegalEx.getReason());

        String badDate = "{\"resourceType\":\"Patient\","
                + "\"identifier\":[{\"value\":\"1001\"}],"
                + "\"name\":[{\"family\":\"Zhang\"}],\"birthDate\":\"not-a-date\"}";
        IngestionException dateEx =
                assertThrows(IngestionException.class, () -> parser.parse(badDate));
        log.info("[TEST INPUT] FHIR bad date -> [TEST BASIS] reason={}", dateEx.getReason());
        assertEquals(IngestionException.RejectionReason.INVALID_DATE, dateEx.getReason());
    }
}
