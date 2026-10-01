package com.github.lechandonga.clinicalpatientindexjava.audit;

import com.github.lechandonga.clinicalpatientindexjava.config.CpiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 审计日志：内存环形缓冲 + SLF4J 双通道。
 * 审计记录只含伪名/摘要，绝不含明文敏感字段。
 */
@Service
public class AuditService {

    public static final String INGEST = "INGEST";
    public static final String QUERY = "QUERY";
    public static final String AUDIT_READ = "AUDIT_READ";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String ALLOWED = "ALLOWED";
    public static final String DENIED = "DENIED";

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final int capacity;
    private final List<AuditEvent> events = new ArrayList<>();

    public AuditService(CpiProperties properties) {
        this.capacity = properties.getAudit().getInMemoryCapacity();
    }

    public synchronized void record(String action, String clientId, String path,
                                    String targetPseudonym, String decision,
                                    String reasonCode, String inputDigest) {
        AuditEvent event = new AuditEvent(System.currentTimeMillis(), clientId, action, path,
                targetPseudonym, decision, reasonCode, inputDigest);
        if (events.size() >= capacity) {
            events.remove(0);
        }
        events.add(event);
        log.info("audit action={} client={} target={} decision={} reason={} inputDigest={}",
                action, clientId, targetPseudonym, decision, reasonCode, inputDigest);
    }

    public void record(String action, String clientId, String path, String decision, String reasonCode) {
        record(action, clientId, path, null, decision, reasonCode, null);
    }

    public synchronized List<AuditEvent> list(String clientId) {
        List<AuditEvent> snapshot = List.copyOf(events);
        record(AUDIT_READ, clientId, "/api/audit/events", null, ALLOWED, "AUDIT_TRAIL_READ", null);
        return Collections.unmodifiableList(snapshot);
    }

    public synchronized void reset() {
        events.clear();
    }
}
