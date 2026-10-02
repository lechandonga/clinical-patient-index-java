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
    private final ReviewService reviewService;

    public IngestionService(List<MessageParser> parsers,
                            MasterPatientIndex index,
                            Pseudonymizer pseudonymizer,
                            AuditLog auditLog,
                            ReviewService reviewService) {
        this.parsers = parsers;
        this.index = index;
        this.pseudonymizer = pseudonymizer;
        this.auditLog = auditLog;
        this.reviewService = reviewService;
    }

    public IngestResponse ingest(String contentType, String body, String actor) {
        ParseOutcome parsed = parse(contentType, body, actor, "INGEST");
        NormalizedPatient patient = parsed.patient();
        MessageParser parser = parsed.parser();

        log.info("[INGEST ACCEPT] actor={} format={} inputSummary={}",
                actor, parser.formatVersion(), patient.summary());

        MasterPatientIndex.MergeResult result;
        try {
            // 冲突钩子在归并锁内、抛出前执行：原子登记复核待办。
            // 对外仍抛出原始 CONTRADICTORY_DATA（钩子抛 REVIEW_QUEUE_FULL 时除外），
            // 老接入方看到的冲突响应与之前完全一致。
            result = index.upsert(patient, conflict ->
                    reviewService.registerConflict(conflict, patient, actor));
        } catch (IngestionException e) {
            log.warn("[MERGE REJECT] actor={} reason={} basis={} inputSummary={}",
                    actor, e.getReason(), e.getMessage(), patient.summary());
            // 登记钩子已写 INGEST_CONFLICT 审计；这里补正式接入维度留痕，
            // detail 不含明文（冲突详情里只有 MPI id 与 canonical key 系统名）。
            auditLog.record(actor, "INGEST", null, null,
                    firstIdentifierHash(patient), "REJECTED:" + e.getReason(),
                    "rejected at ingestion (" + e.getReason()
                            + "); see INGEST_CONFLICT for review routing");
            throw e;
        }

        String pseudonym = pseudonymizer.pseudonym(result.mpiId());
        log.info("[MERGE OK] actor={} mpiId={} pseudonym={} created={} merged={} basis={}",
                actor, result.mpiId(), pseudonym, result.created(), result.merged(), result.basis());
        List<String> safeBasis = result.basis().stream()
                .map(e -> IngestionSupport.hashIdentifierValues(e, pseudonymizer))
                .toList();
        auditLog.record(actor, "INGEST", result.mpiId(), pseudonym,
                firstIdentifierHash(patient), "ACCEPTED",
                (result.created() ? "created;" : "existing;") + "basis=" + safeBasis);
        return new IngestResponse(result.mpiId(), pseudonym, result.created(), result.merged(),
                result.basis(), patient.getNotes());
    }

    /**
     * 接入预检：与正式接入同一套 parser、同一套拒绝原因和同一套归一规则；
     * 之后在索引快照上模拟归并，完全只读。
     */
    public ReviewService.PreviewResult preview(String contentType, String body, String actor) {
        ParseOutcome parsed = parse(contentType, body, actor, "PREVIEW");
        return reviewService.preview(parsed.patient(), parsed.parser().formatVersion(), actor);
    }

    private ParseOutcome parse(String contentType, String body, String actor, String action) {
        MessageParser parser = parsers.stream()
                .filter(p -> p.supports(contentType, body))
                .findFirst()
                .orElseThrow(() -> new IngestionException(
                        IngestionException.RejectionReason.UNSUPPORTED_FORMAT,
                        "cannot determine message format from content-type '" + contentType
                                + "' and payload head"));

        try {
            NormalizedPatient patient = parser.parse(body);
            return new ParseOutcome(parser, patient);
        } catch (IngestionException e) {
            log.warn("[{} REJECT] actor={} format={} reason={} inputSummary={}",
                    action, actor, parser.formatVersion(), e.getReason(), inputSummary(body));
            // 写入审计前把可能出现的 canonical 标识符值替换成哈希，
            // 保证审计不含原始标识符明文（原因码与对外拒绝完全一致）。
            auditLog.record(actor, action, null, null, null,
                    "REJECTED:" + e.getReason(),
                    IngestionSupport.hashIdentifierValues(e.getMessage(), pseudonymizer));
            throw e;
        }
    }

    private record ParseOutcome(MessageParser parser, NormalizedPatient patient) {
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
