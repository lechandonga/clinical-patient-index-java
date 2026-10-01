package com.github.lechandonga.clinicalpatientindexjava.service;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditLog;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.ingest.MessageParser;
import com.github.lechandonga.clinicalpatientindexjava.privacy.Pseudonymizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final List<MessageParser> parsers;
    private final MasterPatientIndex index;
    private final Pseudonymizer pseudonymizer;
    private final AuditLog auditLog;

    public IngestionService(List<MessageParser> parsers,
                            MasterPatientIndex index,
                            Pseudonymizer pseudonymizer,
                            AuditLog auditLog) {
        this.parsers = parsers;
        this.index = index;
        this.pseudonymizer = pseudonymizer;
        this.auditLog = auditLog;
    }

    public IngestResponse ingest(String contentType, String body, String actor) {
        MessageParser parser = parsers.stream()
                .filter(p -> p.supports(contentType, body))
                .findFirst()
                .orElseThrow(() -> new IngestionException(
                        IngestionException.RejectionReason.UNSUPPORTED_FORMAT,
                        "cannot determine message format from content-type '" + contentType
                                + "' and payload head"));

        NormalizedPatient patient;
        try {
            patient = parser.parse(body);
        } catch (IngestionException e) {
            log.warn("[INGEST REJECT] actor={} format={} reason={} detail={} inputSummary={}",
                    actor, parser.formatVersion(), e.getReason(), e.getMessage(), inputSummary(body));
            auditLog.record(actor, "INGEST", null, null, null,
                    "REJECTED:" + e.getReason(), e.getMessage());
            throw e;
        }

        log.info("[INGEST ACCEPT] actor={} format={} inputSummary={}",
                actor, parser.formatVersion(), patient.summary());

        MasterPatientIndex.MergeResult result;
        try {
            result = index.upsert(patient);
        } catch (IngestionException e) {
            log.warn("[MERGE REJECT] actor={} reason={} basis={} inputSummary={}",
                    actor, e.getReason(), e.getMessage(), patient.summary());
            auditLog.record(actor, "INGEST", null, null,
                    firstIdentifierHash(patient), "REJECTED:" + e.getReason(), e.getMessage());
            throw e;
        }

        String pseudonym = pseudonymizer.pseudonym(result.mpiId());
        log.info("[MERGE OK] actor={} mpiId={} pseudonym={} created={} merged={} basis={}",
                actor, result.mpiId(), pseudonym, result.created(), result.merged(), result.basis());
        List<String> safeBasis = result.basis().stream()
                .map(this::hashIdentifierValues)
                .toList();
        auditLog.record(actor, "INGEST", result.mpiId(), pseudonym,
                firstIdentifierHash(patient), "ACCEPTED",
                (result.created() ? "created;" : "existing;") + "basis=" + safeBasis);
        return new IngestResponse(result.mpiId(), pseudonym, result.created(), result.merged(),
                result.basis(), patient.getNotes());
    }

    /**
     * basis 文本形如 "matched identifier HOSPITAL_A|7001"，写入审计前把标识符值替换为哈希。
     */
    private String hashIdentifierValues(String basisEntry) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("([A-Z0-9_]+\\|)([^\\s\\]]+)").matcher(basisEntry);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement(
                            matcher.group(1) + pseudonymizer.hashIdentifier(
                                    matcher.group(1) + matcher.group(2))));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String firstIdentifierHash(NormalizedPatient patient) {
        return patient.getIdentifiers().stream().findFirst()
                .map(PatientIdentifier::canonicalKey).map(pseudonymizer::hashIdentifier)
                .orElse(null);
    }

    private static String inputSummary(String body) {
        if (body == null) {
            return "null";
        }
        String compact = body.strip().replaceAll("\\s+", " ");
        return compact.length() <= 120 ? compact : compact.substring(0, 120) + "...";
    }

    public record IngestResponse(String mpiId, String pseudonym, boolean created,
                                 boolean merged, List<String> mergeBasis, List<String> normalizationNotes) {
    }
}
