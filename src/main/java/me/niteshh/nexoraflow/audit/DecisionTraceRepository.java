package me.niteshh.nexoraflow.audit;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import me.niteshh.nexoraflow.common.RequestType;

public interface DecisionTraceRepository extends JpaRepository<DecisionTrace, String> {

    Optional<DecisionTrace> findFirstByRequestTypeAndRequestIdOrderByEvaluationNumberDesc(RequestType requestType, String requestId);

    List<DecisionTrace> findByRequestTypeAndRequestIdOrderByEvaluationNumber(RequestType requestType, String requestId);

    int countByRequestTypeAndRequestId(RequestType requestType, String requestId);
}
