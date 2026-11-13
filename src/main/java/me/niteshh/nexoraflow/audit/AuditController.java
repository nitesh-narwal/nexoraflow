package me.niteshh.nexoraflow.audit;

import java.time.Instant;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.niteshh.nexoraflow.common.web.PageResponse;
import me.niteshh.nexoraflow.common.web.Specs;
import me.niteshh.nexoraflow.configuration.JsonSupport;

@RestController
@RequestMapping("/api/v1/audit-events")
@Tag(name = "Audit", description = "Append-only audit and workflow events")
public class AuditController {

    private final AuditEventRepository events;
    private final JsonSupport json;

    public AuditController(AuditEventRepository events, JsonSupport json) {
        this.events = events;
        this.json = json;
    }

    @GetMapping
    @Operation(summary = "Search audit events (entity, event type, category, actor, time window)")
    public PageResponse<AuditDtos.AuditEventResponse> search(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) AuditCategory category,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 50, sort = {"occurredAt", "eventId"}) Pageable pageable) {
        var spec = Specs.<AuditEvent>allOf(Specs.<AuditEvent>eq("entityType", entityType), Specs.<AuditEvent>eq("entityId", entityId),
                Specs.<AuditEvent>eq("eventType", eventType), Specs.<AuditEvent>eq("eventCategory", category),
                Specs.<AuditEvent>eq("actor", actor), Specs.<AuditEvent, Instant>gte("occurredAt", from),
                Specs.<AuditEvent, Instant>lte("occurredAt", to));
        return PageResponse.of(events.findAll(spec, pageable), e -> AuditDtos.AuditEventResponse.from(e, json));
    }
}
