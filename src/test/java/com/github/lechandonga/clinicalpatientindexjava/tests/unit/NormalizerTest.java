package com.github.lechandonga.clinicalpatientindexjava.tests.unit;

import com.github.lechandonga.clinicalpatientindexjava.config.CpiProperties;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedIdentifier;
import com.github.lechandonga.clinicalpatientindexjava.ingest.ErrorCode;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IdentifierNormalizer;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IngestException;
import com.github.lechandonga.clinicalpatientindexjava.ingest.TimeNormalizer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NormalizerTest {

    private static final Logger log = LoggerFactory.getLogger(NormalizerTest.class);

    private final IdentifierNormalizer normalizer;

    NormalizerTest() {
        CpiProperties properties = new CpiProperties();
        properties.getIdentifier().getSystemAliases().put("EMR", "HIS");
        properties.getIdentifier().setStripPrefixes(List.of("MRN", "MR"));
        this.normalizer = new IdentifierNormalizer(properties);
    }

    @Test
    void normalizesCasePrefixesSeparatorsAndLeadingZeros() {
        String[][] cases = {
                {" his ", "MRN-00012345", "HIS|12345"},
                {"emr", "mr 0012345", "HIS|12345"},
                {"hospital.a", "aBc_987", "HOSPITAL.A|ABC987"},
        };
        for (String[] testCase : cases) {
            NormalizedIdentifier result = normalizer.normalize(testCase[0], testCase[1], "MR");
            log.info("标识符归一判定 input={}/{} -> canonical={} 依据=去空白/大写/别名/剥离前缀/去分隔符/去前导零",
                    testCase[0], testCase[1], result.canonicalKey());
            assertEquals(testCase[2], result.canonicalKey());
        }
    }

    @Test
    void rejectsInvalidIdentifiersWithDistinctReasons() {
        IngestException emptyValue = assertThrows(IngestException.class,
                () -> normalizer.normalize("HIS", "   ", "MR"));
        assertEquals(ErrorCode.MISSING_REQUIRED_FIELD, emptyValue.getCode());

        IngestException emptySystem = assertThrows(IngestException.class,
                () -> normalizer.normalize(" ", "12345", "MR"));
        assertEquals(ErrorCode.IDENTIFIER_INVALID, emptySystem.getCode());

        IngestException emptyAfterCompact = assertThrows(IngestException.class,
                () -> normalizer.normalize("HIS", "---", "MR"));
        assertEquals(ErrorCode.IDENTIFIER_INVALID, emptyAfterCompact.getCode());
        log.info("非法标识符判定覆盖: 空值=MISSING_REQUIRED_FIELD, 空系统/归一为空=IDENTIFIER_INVALID");
    }

    @Test
    void normalizesMultipleTimeFormatsAndRejectsBadOnes() {
        assertEquals("1984-03-07", TimeNormalizer.normalizeBirthDate("19840307"));
        assertEquals("1984-03-07", TimeNormalizer.normalizeBirthDate("1984-03-07T10:00:00+08:00"));
        assertEquals("1984-03-07", TimeNormalizer.normalizeBirthDate("19840307120000"));

        IngestException badDate = assertThrows(IngestException.class,
                () -> TimeNormalizer.normalizeBirthDate("1984-13-40"));
        assertEquals(ErrorCode.TIME_FORMAT_ERROR, badDate.getCode());

        IngestException missing = assertThrows(IngestException.class,
                () -> TimeNormalizer.normalizeBirthDate(" "));
        assertEquals(ErrorCode.MISSING_REQUIRED_FIELD, missing.getCode());
        log.info("时间判定: 19840307 / ISO / 14位紧凑格式均补全为 1984-03-07；非法历法=TIME_FORMAT_ERROR");
    }
}
