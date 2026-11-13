package me.niteshh.nexoraflow.acceptance;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.support.AbstractIntegrationTest;

/** The generation / validation / evaluation / reporting endpoints, end to end. */
@AutoConfigureMockMvc
class GeneratorApiAcceptanceTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Override
    protected boolean resetBeforeEachTest() {
        return false;
    }

    @BeforeEach
    void noSimulatedClock() {
        AppClock.clearSimulatedTime();
    }

    @Test
    void generateValidateEvaluateAndReportThroughTheApi() throws Exception {
        mvc.perform(get("/api/v1/generator/status")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("IDLE"));
        mvc.perform(post("/api/v1/generator/run").contentType(MediaType.APPLICATION_JSON).content("{\"command\":\"explode\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Unknown command")));
        mvc.perform(post("/api/v1/generator/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"generate-all\",\"size\":\"SMALL\",\"seed\":20261022,\"referenceDate\":\"2026-10-22\",\"async\":false}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("COMPLETED")).andExpect(jsonPath("$.command").value("GENERATE_ALL"));
        mvc.perform(get("/api/v1/generator/status")).andExpect(jsonPath("$.state").value("COMPLETED")).andExpect(jsonPath("$.result.stages", org.hamcrest.Matchers.hasSize(3)));

        mvc.perform(get("/api/v1/validation/report")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/validation/run")).andExpect(status().isOk()).andExpect(jsonPath("$.summary.allPassed").value(true))
                .andExpect(jsonPath("$.checks[*].id", hasItem("BI-09")));
        mvc.perform(get("/api/v1/validation/report")).andExpect(status().isOk()).andExpect(jsonPath("$.summary.failed").value(0));

        mvc.perform(post("/api/v1/evaluation/run")).andExpect(status().isOk()).andExpect(jsonPath("$.anomalyDetection.recall").value(1.0))
                .andExpect(jsonPath("$.anomalyDetection.falsePositives").value(0)).andExpect(jsonPath("$.workflowDecisions.decisionAccuracy").value(1.0));
        mvc.perform(get("/api/v1/evaluation/report")).andExpect(status().isOk()).andExpect(jsonPath("$.totals.totalRecords", greaterThan(1000)));

        mvc.perform(get("/api/v1/employees").param("size", "1")).andExpect(jsonPath("$.totalElements").value(50));
        mvc.perform(get("/api/v1/reports/summary")).andExpect(status().isOk()).andExpect(jsonPath("$.financial.totals.eligible_reimbursement_usd", greaterThan(0.0)));
        mvc.perform(get("/api/v1/leave-requests").param("status", "FLAGGED")).andExpect(jsonPath("$.content[*].decisionReason", hasItem("OVERLAPPING_APPROVED_LEAVE")));
        mvc.perform(get("/api/v1/audit-events").param("eventType", "APPROVAL_ATTEMPT_REJECTED")).andExpect(jsonPath("$.totalElements", greaterThan(0)));
    }
}
