package me.niteshh.nexoraflow.validation;

import java.util.List;

/** Structured validation outcome: every check, its status and the records that violate it. */
public record ValidationReport(Summary summary, List<CheckResult> checks) {

    public record Summary(int checks, int passed, int failed, int informational, long totalFindings, boolean allPassed) {
    }

    /**
     * @param severity ERROR failures make the report fail; INFO checks only document expected, explained conditions
     *                 (for example preserved invalid submissions).
     */
    public record CheckResult(String id, String category, String name, String description, String severity, String status,
                              long findingCount, List<Finding> findings) {
    }

    public record Finding(String entityType, String entityId, String message) {
    }
}
