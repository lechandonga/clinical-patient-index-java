package com.github.lechandonga.clinicalpatientindexjava.tests.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.clinicalpatientindexjava.config.CpiProperties;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.ingest.ErrorCode;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IdentifierNormalizer;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IngestException;
import com.github.lechandonga.clinicalpatientindexjava.ingest.fhir.FhirPatientParser;
import com.github.lechandonga.clinicalpatientindexjava.ingest.hl7.Hl7v2PatientParser;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {

    private static final Logger log = LoggerFactory.getLogger(ParserTest.class);

    private final IdentifierNormalizer identifierNormalizer =
            new IdentifierNormalizer(new CpiProperties());
    private final Hl7v2PatientParser hl7Parser = new Hl7v2PatientParser(identifierNormalizer);
    private final FhirPatientParser fhirParser =
            new FhirPatientParser(new ObjectMapper(), identifierNormalizer);

    private static final String HL7_A01 = """
            MSH|^~\\&|HIS_APP|HIS|LAB_APP|LIS|20261001120000||ADT^A01|MSG00001|P|2.5.1
            EVN|A01|20261001120000
            PID|1||MRN-00012345^^^HIS^MR~P-999^^^LIS^MR||张^三||19840307|M|||北京市朝阳区^街道^城市||13800000000
            """;

    private static final String FHIR_PATIENT = """
            {
              "resourceType": "Patient",
              "id": "p1",
              "identifier": [
                {"system": "HIS", "value": "mrn-00012345", "type": {"coding": [{"code": "MR"}]}},
                {"system": "LIS", "value": "P999"}
              ],
              "name": [{"use": "official", "family": "张", "given": ["三"]}],
              "gender": "male",
              "birthDate": "1984-03-07",
              "telecom": [{"system": "phone", "value": "13800000000"}]
            }
            """;

    @Test
    void parsesHl7AndFhirToSameCanonicalIdentifiers() {
        NormalizedPatient fromHl7 = hl7Parser.parse(HL7_A01);
        NormalizedPatient fromFhir = fhirParser.parse(FHIR_PATIENT);
        assertEquals(2, fromHl7.getIdentifiers().size());
        assertEquals(fromHl7.getIdentifiers().get(0).canonicalKey(),
                fromFhir.getIdentifiers().get(0).canonicalKey());
        assertEquals("1984-03-07", fromHl7.getBirthDate());
        assertEquals("1984-03-07", fromFhir.getBirthDate());
        log.info("输入摘要 HL7 长度={} FHIR 长度={}；判定依据=HIS/MRN-00012345 与 HIS/mrn-00012345 归一为同一 canonicalKey",
                HL7_A01.length(), FHIR_PATIENT.length());
    }

    @Test
    void acceptsOutOfOrderSegmentsButRejectsStructuralProblems() {
        String msh = HL7_A01.lines().findFirst().orElseThrow();
        String reordered = msh + "\r" + HL7_A01.lines().skip(2).findFirst().orElseThrow()
                + "\r" + HL7_A01.lines().skip(1).findFirst().orElseThrow();
        NormalizedPatient patient = hl7Parser.parse(reordered);
        assertEquals("1984-03-07", patient.getBirthDate());
        log.info("乱序输入摘要: EVN/PID 顺序交换（MSH 仍为首段）；判定依据=按段名定位 PID 而非固定位置");

        IngestException noMsh = assertThrows(IngestException.class,
                () -> hl7Parser.parse("PID|1||123^^^HIS"));
        assertEquals(ErrorCode.FORMAT_ERROR, noMsh.getCode());

        String duplicatedPid = HL7_A01 + "\rPID|1||X^^^HIS||李^四||19900101|F";
        IngestException duplicated = assertThrows(IngestException.class,
                () -> hl7Parser.parse(duplicatedPid));
        assertEquals(ErrorCode.FORMAT_ERROR, duplicated.getCode());

        IngestException badVersion = assertThrows(IngestException.class,
                () -> hl7Parser.parse(HL7_A01.replace("2.5.1", "3.0")));
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, badVersion.getCode());

        IngestException badEvent = assertThrows(IngestException.class,
                () -> hl7Parser.parse(HL7_A01.replace("ADT^A01", "ADT^A99")));
        assertEquals(ErrorCode.FORMAT_ERROR, badEvent.getCode());
        log.info("结构判定: 缺MSH/PID重复=FORMAT_ERROR, 版本3.0=UNSUPPORTED_VERSION, A99=FORMAT_ERROR");
    }

    @Test
    void distinguishesMissingFieldsFromFormatAndTimeErrors() {
        IngestException missingPid = assertThrows(IngestException.class,
                () -> hl7Parser.parse("MSH|^~\\&|A|B|C|D|20261001||ADT^A01|m|P|2.5.1\rEVN|A01"));
        assertEquals(ErrorCode.MISSING_REQUIRED_FIELD, missingPid.getCode());

        IngestException badTime = assertThrows(IngestException.class,
                () -> hl7Parser.parse(HL7_A01.replace("19840307", "19840230")));
        assertEquals(ErrorCode.TIME_FORMAT_ERROR, badTime.getCode());

        String badJson = "{\"resourceType\":\"Patient\", broken";
        IngestException formatError = assertThrows(IngestException.class,
                () -> fhirParser.parse(badJson));
        assertEquals(ErrorCode.FORMAT_ERROR, formatError.getCode());

        String missingIdentifier = FHIR_PATIENT.replaceAll("(?s)\"identifier\".*?\\],", "");
        IngestException missingId = assertThrows(IngestException.class,
                () -> fhirParser.parse(missingIdentifier));
        assertEquals(ErrorCode.MISSING_REQUIRED_FIELD, missingId.getCode());

        String wrongResource = FHIR_PATIENT.replace("Patient", "Observation");
        IngestException wrongType = assertThrows(IngestException.class,
                () -> fhirParser.parse(wrongResource));
        assertEquals(ErrorCode.FORMAT_ERROR, wrongType.getCode());
        log.info("输入摘要=缺PID/非法日期/坏JSON/缺identifier/错误资源类型；失败原因分别可区分");
    }

    @Test
    void snifferDistinguishesFormats() {
        assertTrue(hl7Parser.supports(HL7_A01));
        assertFalse(hl7Parser.supports(FHIR_PATIENT));
        assertTrue(fhirParser.supports(FHIR_PATIENT));
    }
}
