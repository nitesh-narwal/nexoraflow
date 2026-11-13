package me.niteshh.nexoraflow.audit;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DetectedAnomalyRepository extends JpaRepository<DetectedAnomaly, String> {

    List<DetectedAnomaly> findByEntityTypeAndEntityIdOrderByAnomalyId(String entityType, String entityId);
}
