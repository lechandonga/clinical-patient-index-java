package com.github.lechandonga.clinicalpatientindexjava.ingest;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditService;
import com.github.lechandonga.clinicalpatientindexjava.deid.DeIdentificationService;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedIdentifier;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;
import com.github.lechandonga.clinicalpatientindexjava.mpi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.mpi.MergeResult;
import com.github.lechandonga.clinicalpatientindexjava.security.ClientPrincipal;
import com.github.lechandonga.clinicalpatientindexjava.web.dto.IngestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** 编排：格式嗅探 → 解析归一 → 来源系统授权 → 主索引合并 → 审计。 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final List<PatientParser> parsers;
    private final MasterPatientIndex index;
    private final AuditService auditService;

    public IngestionService(List<PatientParser> parsers, MasterPatientIndex index, AuditService auditService) {
        this.parsers = parsers;
        this.index = index;
        this.auditService = auditService;
    }

    public IngestResponse ingest(String raw, ClientPrincipal principal) {
        String inputDigest = DeIdentificationService.fingerprint(raw);
        PatientParser parser = parsers.stream()
                .filter(candidate -> candidate.supports(raw))
                .findFirst()
                .orElseThrow(() -> {
                    auditService.record(AuditService.INGEST, principal.clientId(),
                            ingestPath(raw), null, AuditService.DENIED,
                            ErrorCode.FORMAT_ERROR.name(), inputDigest);
                    return new IngestException(ErrorCode.FORMAT_ERROR,
                            "无法识别的输入格式：既不是 HL7 v2 MSH 消息，也不是 FHIR JSON 资源");
                });

        NormalizedPatient patient;
        try {
            patient = parser.parse(raw);
        } catch (IngestException e) {
            log.info("输入被拒绝 format={} digest={} reason={} detail={}",
                    parser.formatName(), inputDigest, e.getCode(), e.getMessage());
            auditService.record(AuditService.INGEST, principal.clientId(),
                    "/api/ingest/" + parser.formatName().toLowerCase(), null,
                    AuditService.DENIED, e.getCode().name(), inputDigest);
            throw e;
        }

        for (NormalizedIdentifier identifier : patient.getIdentifiers()) {
            if (!principal.systemAllowed(identifier.system())) {
                log.info("来源系统越权 client={} system={} digest={}",
                        principal.clientId(), identifier.system(), inputDigest);
                auditService.record(AuditService.INGEST, principal.clientId(),
                        "/api/ingest", null, AuditService.DENIED,
                        ErrorCode.FORBIDDEN_SCOPE.name(), inputDigest);
                throw new IngestException(ErrorCode.FORBIDDEN_SCOPE,
                        "调用方无权提交来源系统 " + identifier.system() + " 的数据");
            }
        }

        MergeResult result = index.upsert(patient);
        log.info("接入判定 format={} digest={} masterId={} created={} mergedGroups={} identifierCount={} basis={}",
                parser.formatName(), inputDigest, result.masterId(), result.created(),
                result.mergedGroups(), patient.getIdentifiers().size(),
                patient.getIdentifiers().stream().map(NormalizedIdentifier::canonicalKey).toList());
        auditService.record(AuditService.INGEST, principal.clientId(),
                "/api/ingest/" + parser.formatName().toLowerCase(), result.masterId(),
                AuditService.ALLOWED,
                result.created() ? "NEW_MASTER_RECORD" : "MERGED_BY_SHARED_IDENTIFIER",
                inputDigest);
        return new IngestResponse(result.masterId(), result.created(), result.mergedGroups(),
                result.warnings(), parser.formatName());
    }

    private static String ingestPath(String raw) {
        String head = raw == null ? "" : raw.stripLeading();
        return head.startsWith("{") ? "/api/ingest/fhir" : "/api/ingest/hl7";
    }
}
