package me.niteshh.nexoraflow.audit;

import java.util.Map;

import org.springframework.stereotype.Service;

import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.common.id.IdService;
import me.niteshh.nexoraflow.configuration.JsonSupport;
import me.niteshh.nexoraflow.configuration.NexoraProperties;

/** Persists one immutable explanation record per evaluation of a request. */
@Service
public class DecisionTraceService {

    private final DecisionTraceRepository traces;
    private final IdService ids;
    private final AppClock clock;
    private final JsonSupport json;
    private final NexoraProperties properties;

    public DecisionTraceService(DecisionTraceRepository traces, IdService ids, AppClock clock, JsonSupport json,
                                NexoraProperties properties) {
        this.traces = traces;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
        this.properties = properties;
    }

    public DecisionTrace record(RequestType type, String requestId, String decision, String reasonCodes,
                                String policyRef, Map<String, Object> trace) {
        DecisionTrace t = new DecisionTrace();
        t.setTraceId(ids.decisionTraceId());
        t.setRequestType(type);
        t.setRequestId(requestId);
        t.setEvaluationNumber(traces.countByRequestTypeAndRequestId(type, requestId) + 1);
        t.setEvaluatedAt(clock.now());
        t.setDecision(decision);
        t.setReasonCodes(reasonCodes);
        t.setPolicyRef(policyRef);
        t.setRuleVersion(properties.businessRulesVersion());
        t.setTrace(json.toJson(trace));
        return traces.save(t);
    }
}
