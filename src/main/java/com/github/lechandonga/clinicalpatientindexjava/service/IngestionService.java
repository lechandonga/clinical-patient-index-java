package com.github.lechandonga.clinicalpatientindexjava.service;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditLog;
import com.github.lechandonga.clinicalpatientindexjava.domain.ConflictDetectedException;
import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex.FieldDecision;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex.IdentifierDecision;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex.UpsertPlan;
import com.github.lechandonga.clinicalpatientindexjava.ingest.MessageParser;
import com.github.lechandonga.clinicalpatientindexjava.privacy.Pseudonymizer;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewQueue;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final List<MessageParser> parsers;
    private final MasterPatientIndex index;
    private final Pseudonymizer pseudonymizer;
    private final AuditLog auditLog;
    private final ReviewQueue reviewQueue;

    public IngestionService(List<MessageParser> parsers,
                            MasterPatientIndex index,
                            Pseudonymizer pseudonymizer,
                            AuditLog auditLog,
                            ReviewQueue reviewQueue) {
        this.parsers = parsers;
        this.index = index;
        this.pseudonymizer = pseudonymizer;
        this.auditLog = auditLog;
        this.reviewQueue = reviewQueue;
    }

    public IngestResponse ingest(String contentType, String body, String actor) {
        MessageParser parser = resolveParser(contentType, body);

        NormalizedPatient patient;
        try {
            patient = parser.parse(body);
        } catch (IngestionException e) {
            log.warn("[INGEST REJECT] actor={} format={} reason={} inputSummary={}",
                    actor, parser.formatVersion(), e.getReason(), inputSummary(body));
            auditLog.record(actor, "INGEST", null, null, null,
                    "REJECTED:" + e.getReason(), "rejected during parse (no plaintext retained)");
            throw e;
        }

        log.info("[INGEST ACCEPT] actor={} format={} inputSummary={}",
                actor, parser.formatVersion(), patient.summary());

        MasterPatientIndex.MergeResult result;
        try {
            result = index.upsert(patient);
        } catch (ConflictDetectedException e) {
            registerConflictReview(e, patient, actor);
            throw e;
        } catch (IngestionException e) {
            log.warn("[MERGE REJECT] actor={} reason={} inputSummary={}",
                    actor, e.getReason(), patient.summary());
            auditLog.record(actor, "INGEST", null, null,
                    firstIdentifierHash(patient), "REJECTED:" + e.getReason(),
                    "rejected during merge (no plaintext retained)");
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
     * 接入预检：与正式接入走完全相同的解析与归并判定，但只推演不提交。
     * 同一条消息重复预检结果一致；无论消息会不会被拒绝或触发冲突，主索引、
     * 标识归属与已有数据均不发生变化。预检本身留痕，留痕不含明文敏感信息。
     */
    public PrecheckResponse precheck(String contentType, String body, String actor) {
        MessageParser parser = resolveParser(contentType, body);

        NormalizedPatient patient;
        try {
            patient = parser.parse(body);
        } catch (IngestionException e) {
            log.warn("[PRECHECK REJECT] actor={} format={} reason={} inputSummary={}",
                    actor, parser.formatVersion(), e.getReason(), inputSummary(body));
            auditLog.record(actor, "PRECHECK", null, null, null,
                    "REJECTED:" + e.getReason(),
                    "precheck rejected during parse; same ruleset as ingest (no plaintext retained)");
            return PrecheckResponse.rejected(e.getReason().name(),
                    "message would be rejected by formal ingest for the same reason",
                    parser.formatVersion());
        }

        log.info("[PRECHECK EVAL] actor={} format={} inputSummary={}",
                actor, parser.formatVersion(), patient.summary());

        try {
            UpsertPlan plan = index.planUpsert(patient);
            PrecheckResponse response = buildAcceptedPreview(plan, patient, parser.formatVersion());
            log.info("[PRECHECK RESULT] actor={} outcome={} targetPseudonym={} absorbed={} fields={}",
                    actor, response.outcome(), response.targetPseudonym(),
                    response.absorbedPseudonyms(),
                    response.fieldDecisions().stream().map(FieldDecision::field).toList());
            auditLog.record(actor, "PRECHECK", plan.survivorMpiId(),
                    plan.createNew() ? null : pseudonymizer.pseudonym(plan.survivorMpiId()),
                    firstIdentifierHash(patient), "WOULD_ACCEPT",
                    "outcome=" + response.outcome()
                            + "; targetPseudonym=" + response.targetPseudonym()
                            + "; identifierEffects=" + safeIdentifierEffects(plan.identifierDecisions())
                            + "; fieldEffects=" + response.fieldDecisions().stream()
                                .map(d -> d.field() + "=" + d.effect()).toList());
            return response;
        } catch (ConflictDetectedException e) {
            log.warn("[PRECHECK CONFLICT] actor={} category={} pseudonyms={},{}",
                    actor, e.getConflictCategory(),
                    pseudonymizer.pseudonym(e.getSurvivorMpiId()),
                    pseudonymizer.pseudonym(e.getConflictingMpiId()));
            auditLog.record(actor, "PRECHECK", e.getSurvivorMpiId(),
                    pseudonymizer.pseudonym(e.getSurvivorMpiId()),
                    firstIdentifierHash(patient), "REJECTED:CONTRADICTORY_DATA",
                    "precheck detected conflict category=" + e.getConflictCategory()
                            + "; survivorPseudonym=" + pseudonymizer.pseudonym(e.getSurvivorMpiId())
                            + "; otherPseudonym=" + pseudonymizer.pseudonym(e.getConflictingMpiId())
                            + "; read-only, no ticket created");
            return PrecheckResponse.conflict(e,
                    pseudonymizer.pseudonym(e.getSurvivorMpiId()),
                    pseudonymizer.pseudonym(e.getConflictingMpiId()),
                    patient, parser.formatVersion());
        } catch (IngestionException e) {
            auditLog.record(actor, "PRECHECK", null, null, firstIdentifierHash(patient),
                    "REJECTED:" + e.getReason(),
                    "precheck rejected during merge; same ruleset as ingest (no plaintext retained)");
            return PrecheckResponse.rejected(e.getReason().name(),
                    "message would be rejected by formal ingest for the same reason",
                    parser.formatVersion());
        }
    }

    private void registerConflictReview(ConflictDetectedException e,
                                        NormalizedPatient patient,
                                        String actor) {
        String pseudonymA = pseudonymizer.pseudonym(e.getSurvivorMpiId());
        String pseudonymB = pseudonymizer.pseudonym(e.getConflictingMpiId());
        ReviewTicket ticket;
        try {
            ticket = reviewQueue.register(e.getSurvivorMpiId(), e.getConflictingMpiId(),
                    pseudonymA, pseudonymB, e.getConflictCategory(), actor);
        } catch (RuntimeException full) {
            log.warn("[MERGE REJECT] actor={} reason=REVIEW_QUEUE_FULL category={} pseudonyms={},{}",
                    actor, e.getConflictCategory(), pseudonymA, pseudonymB);
            auditLog.record(actor, "INGEST", e.getSurvivorMpiId(), pseudonymA,
                    firstIdentifierHash(patient), "REJECTED:REVIEW_QUEUE_FULL",
                    "conflict category=" + e.getConflictCategory()
                            + "; backlog full limit=" + reviewQueue.getMaxPending()
                            + "; no ticket created; survivorPseudonym=" + pseudonymA
                            + "; otherPseudonym=" + pseudonymB);
            throw full;
        }
        boolean deduplicated = ticket.getSubmittedAt() != null
                && reviewQueue.occurrenceCount(ticket) > 1;
        log.warn("[MERGE CONFLICT->REVIEW] actor={} ticket={} category={} pseudonyms={},{} deduplicated={}",
                actor, ticket.getTicketId(), e.getConflictCategory(),
                pseudonymA, pseudonymB, deduplicated);
        auditLog.record(actor, "INGEST", e.getSurvivorMpiId(), pseudonymA,
                firstIdentifierHash(patient), "REJECTED:CONTRADICTORY_DATA",
                "conflict category=" + e.getConflictCategory()
                        + "; reviewTicket=" + ticket.getTicketId()
                        + "; deduplicated=" + deduplicated
                        + "; survivorPseudonym=" + pseudonymA
                        + "; otherPseudonym=" + pseudonymB
                        + "; indices remain separate unchanged");
    }

    private PrecheckResponse buildAcceptedPreview(UpsertPlan plan,
                                                  NormalizedPatient patient,
                                                  String formatVersion) {
        String outcome = plan.createNew() ? "CREATE_NEW"
                : (plan.merged() ? "MERGE_INTO_EXISTING" : "UPDATE_EXISTING");
        String targetPseudonym = plan.createNew()
                ? null : pseudonymizer.pseudonym(plan.survivorMpiId());
        List<String> absorbedPseudonyms = new ArrayList<>();
        for (String mpiId : plan.absorbedMpiIds()) {
            absorbedPseudonyms.add(pseudonymizer.pseudonym(mpiId));
        }
        List<SafeIdentifierEffect> identifierEffects = plan.identifierDecisions().stream()
                .map(d -> new SafeIdentifierEffect(
                        pseudonymizer.hashIdentifier(d.canonicalKey()), d.effect()))
                .toList();
        NormalizedView normalized = new NormalizedView(
                patient.getFamilyName(), patient.getGivenName(),
                patient.getBirthDate(),
                patient.getGender() == null ? null : patient.getGender().name(),
                List.copyOf(patient.getNotes()));
        return new PrecheckResponse(true, null, null, formatVersion, outcome,
                targetPseudonym, absorbedPseudonyms, normalized,
                List.copyOf(plan.fieldDecisions()), identifierEffects,
                null, null, null);
    }

    private List<String> safeIdentifierEffects(List<IdentifierDecision> decisions) {
        return decisions.stream()
                .map(d -> pseudonymizer.hashIdentifier(d.canonicalKey()) + "=" + d.effect())
                .toList();
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

    private MessageParser resolveParser(String contentType, String body) {
        return parsers.stream()
                .filter(p -> p.supports(contentType, body))
                .findFirst()
                .orElseThrow(() -> new IngestionException(
                        IngestionException.RejectionReason.UNSUPPORTED_FORMAT,
                        "cannot determine message format from content-type '" + contentType
                                + "' and payload head"));
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

    public record NormalizedView(String familyName, String givenName, String birthDate,
                                 String gender, List<String> normalizationNotes) {
    }

    public record SafeIdentifierEffect(String identifierHash, String effect) {
    }

    public record PrecheckResponse(boolean acceptable,
                                   String rejectionReason,
                                   String rejectionDetail,
                                   String formatVersion,
                                   String outcome,
                                   String targetPseudonym,
                                   List<String> absorbedPseudonyms,
                                   NormalizedView normalizedFields,
                                   List<FieldDecision> fieldDecisions,
                                   List<SafeIdentifierEffect> identifierEffects,
                                   String conflictCategory,
                                   String conflictPseudonymA,
                                   String conflictPseudonymB) {

        static PrecheckResponse rejected(String reason, String detail, String formatVersion) {
            return new PrecheckResponse(false, reason, detail, formatVersion,
                    "REJECTED", null, List.of(), null, List.of(), List.of(),
                    null, null, null);
        }

        static PrecheckResponse conflict(ConflictDetectedException e,
                                         String pseudonymA,
                                         String pseudonymB,
                                         NormalizedPatient patient,
                                         String formatVersion) {
            return new PrecheckResponse(false, e.getReason().name(),
                    "formal ingest would reject this message and open a manual review ticket",
                    formatVersion, "CONFLICT_REVIEW", null, List.of(),
                    new NormalizedView(patient.getFamilyName(), patient.getGivenName(),
                            patient.getBirthDate(),
                            patient.getGender() == null ? null : patient.getGender().name(),
                            List.copyOf(patient.getNotes())),
                    List.of(), List.of(),
                    e.getConflictCategory(), pseudonymA, pseudonymB);
        }
    }
}
