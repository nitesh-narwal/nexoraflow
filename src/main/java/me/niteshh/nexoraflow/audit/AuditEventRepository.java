package me.niteshh.nexoraflow.audit;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String>, JpaSpecificationExecutor<AuditEvent> {

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByOccurredAtAscEventIdAsc(String entityType, String entityId);
}
