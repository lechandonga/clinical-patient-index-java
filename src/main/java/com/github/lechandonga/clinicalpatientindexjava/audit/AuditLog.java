package com.github.lechandonga.clinicalpatientindexjava.audit;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存审计日志（本地运行/验证用）。
 */
@Component
public class AuditLog {

    private final CopyOnWriteArrayList<AuditEvent> events = new CopyOnWriteArrayList<>();

    public void record(String actor, String action, String mpiId, String pseudonym,
                       String identifierHash, String result, String detail) {
        events.add(new AuditEvent(Instant.now(), actor, action, mpiId, pseudonym,
                identifierHash, result, detail));
    }

    public List<AuditEvent> findAll() {
        return List.copyOf(events);
    }

    public List<AuditEvent> findByActor(String actor) {
        return events.stream().filter(e -> e.actor().equals(actor)).toList();
    }

    /**
     * 仅供本地测试隔离使用。
     */
    public void resetForTesting() {
        events.clear();
    }
}
