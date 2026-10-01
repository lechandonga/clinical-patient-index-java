package com.github.lechandonga.clinicalpatientindexjava.query;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditService;
import com.github.lechandonga.clinicalpatientindexjava.deid.DeIdentificationService;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedIdentifier;
import com.github.lechandonga.clinicalpatientindexjava.ingest.ErrorCode;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IngestException;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IdentifierNormalizer;
import com.github.lechandonga.clinicalpatientindexjava.mpi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.mpi.MasterRecord;
import com.github.lechandonga.clinicalpatientindexjava.security.ClientPrincipal;
import com.github.lechandonga.clinicalpatientindexjava.web.dto.DeidentifiedPatientView;
import org.springframework.stereotype.Service;

import java.util.List;

/** 去标识化查询：所有返回内容都经过伪名化/泛化，且每次访问写审计。 */
@Service
public class PatientQueryService {

    private final MasterPatientIndex index;
    private final IdentifierNormalizer identifierNormalizer;
    private final DeIdentificationService deIdentificationService;
    private final AuditService auditService;

    public PatientQueryService(MasterPatientIndex index,
                               IdentifierNormalizer identifierNormalizer,
                               DeIdentificationService deIdentificationService,
                               AuditService auditService) {
        this.index = index;
        this.identifierNormalizer = identifierNormalizer;
        this.deIdentificationService = deIdentificationService;
        this.auditService = auditService;
    }

    public DeidentifiedPatientView query(String rawSystem, String rawIdentifier, ClientPrincipal principal) {
        var probe = identifierNormalizer.normalize(rawSystem, rawIdentifier, "ID");
        if (!principal.systemAllowed(probe.system())) {
            auditService.record(AuditService.QUERY, principal.clientId(), "/api/patients/find",
                    null, AuditService.DENIED, ErrorCode.FORBIDDEN_SCOPE.name(),
                    DeIdentificationService.fingerprint(rawSystem + "|" + rawIdentifier));
            throw new IngestException(ErrorCode.FORBIDDEN_SCOPE,
                    "调用方无权查询来源系统 " + probe.system());
        }
        MasterRecord record = index.findByIdentifier(probe.system(), probe.value());
        if (record == null) {
            auditService.record(AuditService.QUERY, principal.clientId(), "/api/patients/find",
                    null, AuditService.DENIED, ErrorCode.PATIENT_NOT_FOUND.name(),
                    DeIdentificationService.fingerprint(probe.canonicalKey()));
            throw new IngestException(ErrorCode.PATIENT_NOT_FOUND, "未找到匹配的患者主索引");
        }
        DeidentifiedPatientView view = toView(record);
        auditService.record(AuditService.QUERY, principal.clientId(), "/api/patients/find",
                view.pseudonym(), AuditService.ALLOWED, "QUERY_BY_IDENTIFIER",
                DeIdentificationService.fingerprint(probe.canonicalKey()));
        return view;
    }

    private DeidentifiedPatientView toView(MasterRecord record) {
        List<String> identifierRefs = record.getIdentifiers().keySet().stream()
                .map(deIdentificationService::pseudonym)
                .sorted()
                .toList();
        List<String> systems = record.getIdentifiers().values().stream()
                .map(NormalizedIdentifier::system)
                .distinct()
                .sorted()
                .toList();
        String birthYear = record.getBirthDate() == null ? null : record.getBirthDate().substring(0, 4);
        return new DeidentifiedPatientView(record.getMasterId(), birthYear,
                record.getGender() == null ? "UNK" : record.getGender().name(),
                identifierRefs, systems);
    }
}
