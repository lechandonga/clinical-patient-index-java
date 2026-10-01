package com.github.lechandonga.clinicalpatientindexjava.service;

import com.github.lechandonga.clinicalpatientindexjava.audit.AuditLog;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientRecord;
import com.github.lechandonga.clinicalpatientindexjava.privacy.DeidentifiedPatientView;
import com.github.lechandonga.clinicalpatientindexjava.privacy.Pseudonymizer;
import com.github.lechandonga.clinicalpatientindexjava.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class PatientQueryService {

    private static final Logger log = LoggerFactory.getLogger(PatientQueryService.class);

    private final MasterPatientIndex index;
    private final Pseudonymizer pseudonymizer;
    private final AuditLog auditLog;

    public PatientQueryService(MasterPatientIndex index,
                               Pseudonymizer pseudonymizer,
                               AuditLog auditLog) {
        this.index = index;
        this.pseudonymizer = pseudonymizer;
        this.auditLog = auditLog;
    }

    /**
     * 按源系统标识符查询，返回去标识化视图。
     */
    public DeidentifiedPatientView queryByIdentifier(Caller caller, String system, String value) {
        PatientIdentifier identifier = new PatientIdentifier(system, value);
        MasterPatientRecord record = index.findByIdentifier(identifier);
        String idHash = pseudonymizer.hashIdentifier(identifier.canonicalKey());
        if (record == null) {
            log.info("[QUERY MISS] actor={} identifierHash={} basis=canonical key {}",
                    caller.username(), idHash, identifier.canonicalKey());
            auditLog.record(caller.username(), "QUERY", null, null, idHash, "NOT_FOUND",
                    "lookup by identifier hash");
            return null;
        }
        DeidentifiedPatientView view = toView(record);
        log.info("[QUERY HIT] actor={} identifierHash={} mpiId={} pseudonym={} basis=exact canonical match",
                caller.username(), idHash, record.getMpiId(), view.pseudonym());
        auditLog.record(caller.username(), "QUERY", record.getMpiId(), view.pseudonym(),
                idHash, "OK", "lookup by identifier hash");
        return view;
    }

    public DeidentifiedPatientView queryByMpi(Caller caller, String mpiId) {
        MasterPatientRecord record = index.findByMpiId(mpiId);
        if (record == null) {
            auditLog.record(caller.username(), "QUERY", mpiId, null, null, "NOT_FOUND",
                    "lookup by mpi id");
            return null;
        }
        DeidentifiedPatientView view = toView(record);
        auditLog.record(caller.username(), "QUERY", record.getMpiId(), view.pseudonym(),
                null, "OK", "lookup by mpi id");
        return view;
    }

    private DeidentifiedPatientView toView(MasterPatientRecord record) {
        Integer birthYear = record.getBirthDate() == null
                ? null : Integer.parseInt(record.getBirthDate().substring(0, 4));
        int sourceCount = record.getIdentifiers().stream()
                .map(PatientIdentifier::system).collect(Collectors.toSet()).size();
        return new DeidentifiedPatientView(
                pseudonymizer.pseudonym(record.getMpiId()),
                record.getGender() == null ? "UNKNOWN" : record.getGender().name(),
                birthYear,
                record.getIdentifiers().size(),
                sourceCount);
    }
}
