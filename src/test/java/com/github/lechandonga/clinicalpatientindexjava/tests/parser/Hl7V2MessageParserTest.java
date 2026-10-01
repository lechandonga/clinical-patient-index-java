package com.github.lechandonga.clinicalpatientindexjava.tests.parser;

import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.ingest.Hl7V2MessageParser;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Hl7V2MessageParserTest {

    private static final Logger log = LoggerFactory.getLogger(Hl7V2MessageParserTest.class);

    private final Hl7V2MessageParser parser = new Hl7V2MessageParser();

    private static final String VALID = """
            MSH|^~\\&|HIS_A|HOSPITAL_A|MPI|MPI|20261001120000||ADT^A04^ADT_A01|MSG00001|P|2.5.1
            PID|1||MRN-1001^^^HOSPITAL_A^MR~1002^^^HOSPITAL_B^MR||Zhang^San||19900615|M
            """;

    @Test
    void parsesValidMessageWithCaseAndPrefixNormalization() {
        log.info("[TEST INPUT] HL7 valid: {}", VALID.replace("\n", " | "));
        NormalizedPatient patient = parser.parse(VALID);
        log.info("[TEST BASIS] identifiers={}, name={}, birthDate={}, gender={}",
                patient.getIdentifiers(), patient.fullName(), patient.getBirthDate(), patient.getGender());
        assertEquals(2, patient.getIdentifiers().size());
        assertEquals("1990-06-15", patient.getBirthDate());
        assertEquals("Zhang", patient.getFamilyName());
        assertEquals("San", patient.getGivenName());
    }

    @Test
    void toleratesReorderedSegmentsButRequiresMshFirst() {
        String reordered = VALID.replace(
                "MSH|^~\\&|HIS_A|HOSPITAL_A|MPI|MPI|20261001120000||ADT^A04^ADT_A01|MSG00001|P|2.5.1\n", "")
                .replace("PID|1||",
                        "MSH|^~\\&|HIS_A|HOSPITAL_A|MPI|MPI|20261001120000||ADT^A04^ADT_A01|MSG00001|P|2.5.1\nPID|1||");
        log.info("[TEST INPUT] HL7 reordered segments accepted");
        assertEquals("1990-06-15", parser.parse(reordered).getBirthDate());

        String noMsh = "PID|1||1001^^^HOSPITAL_A||Zhang^San||19900615|M";
        log.info("[TEST INPUT] HL7 missing MSH first: {}", noMsh);
        IngestionException ex = assertThrows(IngestionException.class, () -> parser.parse(noMsh));
        log.info("[TEST BASIS] rejection reason={} detail={}", ex.getReason(), ex.getMessage());
        assertEquals(IngestionException.RejectionReason.MALFORMED_MESSAGE, ex.getReason());
    }

    @Test
    void distinguishesMalformedFromUnsupportedVersionAndEvent() {
        String garbage = "not an hl7 message at all";
        log.info("[TEST INPUT] HL7 garbage: {}", garbage);
        IngestionException garbageEx = assertThrows(IngestionException.class, () -> parser.parse(garbage));
        log.info("[TEST BASIS] reason={}", garbageEx.getReason());
        assertEquals(IngestionException.RejectionReason.MALFORMED_MESSAGE, garbageEx.getReason());

        String badVersion = VALID.replace("2.5.1", "3.0");
        IngestionException versionEx = assertThrows(IngestionException.class, () -> parser.parse(badVersion));
        log.info("[TEST INPUT] version=3.0 -> [TEST BASIS] reason={}", versionEx.getReason());
        assertEquals(IngestionException.RejectionReason.UNSUPPORTED_FORMAT, versionEx.getReason());

        String badEvent = VALID.replace("ADT^A04^ADT_A01", "ADT^A99^ADT_A01");
        IngestionException eventEx = assertThrows(IngestionException.class, () -> parser.parse(badEvent));
        log.info("[TEST INPUT] event=A99 -> [TEST BASIS] reason={}", eventEx.getReason());
        assertEquals(IngestionException.RejectionReason.UNSUPPORTED_FORMAT, eventEx.getReason());
    }

    @Test
    void distinguishesMissingFieldFromIllegalIdentifierAndBadDate() {
        String noName = VALID.replace("Zhang^San", "");
        IngestionException nameEx = assertThrows(IngestionException.class, () -> parser.parse(noName));
        log.info("[TEST INPUT] PID-5 emptied -> [TEST BASIS] reason={}", nameEx.getReason());
        assertEquals(IngestionException.RejectionReason.MISSING_REQUIRED_FIELD, nameEx.getReason());

        String noId = VALID.replace("MRN-1001^^^HOSPITAL_A^MR~1002^^^HOSPITAL_B^MR", "");
        IngestionException idEx = assertThrows(IngestionException.class, () -> parser.parse(noId));
        log.info("[TEST INPUT] PID-3 emptied -> [TEST BASIS] reason={}", idEx.getReason());
        assertEquals(IngestionException.RejectionReason.MISSING_REQUIRED_FIELD, idEx.getReason());

        String illegalId = VALID.replace("MRN-1001", "BAD\tID");
        IngestionException illegalEx = assertThrows(IngestionException.class, () -> parser.parse(illegalId));
        log.info("[TEST INPUT] illegal identifier char -> [TEST BASIS] reason={}", illegalEx.getReason());
        assertEquals(IngestionException.RejectionReason.ILLEGAL_IDENTIFIER, illegalEx.getReason());

        String badDate = VALID.replace("19900615", "19901340");
        IngestionException dateEx = assertThrows(IngestionException.class, () -> parser.parse(badDate));
        log.info("[TEST INPUT] date=19901340 -> [TEST BASIS] reason={}", dateEx.getReason());
        assertEquals(IngestionException.RejectionReason.INVALID_DATE, dateEx.getReason());
    }

    @Test
    void completesPartialGenderWithExplainableNote() {
        String weirdGender = VALID.replace("|M\n", "|Z\n");
        NormalizedPatient patient = parser.parse(weirdGender);
        log.info("[TEST INPUT] gender=Z -> [TEST BASIS] gender={} notes={}",
                patient.getGender(), patient.getNotes());
        assertEquals(com.github.lechandonga.clinicalpatientindexjava.domain.Gender.UNKNOWN,
                patient.getGender());
        assertTrue(patient.getNotes().stream().anyMatch(n -> n.contains("UNKNOWN")));
    }
}
