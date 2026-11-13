package me.niteshh.nexoraflow.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.rules.DecisionAggregator;
import me.niteshh.nexoraflow.common.rules.RuleOutcome;
import me.niteshh.nexoraflow.common.rules.RuleSeverity;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmployeeRules;
import me.niteshh.nexoraflow.employee.EmploymentStatus;
import me.niteshh.nexoraflow.policy.EmployeePolicyAssignment;
import me.niteshh.nexoraflow.policy.LeavePolicy;

/**
 * Deterministic leave evaluation (rules LV-01 .. LV-10). Pure: it never touches the database and never
 * sees ground-truth labels. Approval-route resolvability (LV-11) is appended by the workflow service.
 */
@Component
public class LeaveEvaluationEngine {

    public LeaveEvaluationResult evaluate(LeaveEvaluationInput in) {
        List<RuleOutcome> outcomes = new ArrayList<>();
        Employee employee = in.employee();
        LeavePolicy policy = in.policy();

        outcomes.add(employeeActive(in, employee));
        outcomes.add(policyEffective(in, policy));
        RuleOutcome eligibility = employeeEligible(in, employee, policy);
        outcomes.add(eligibility);

        RuleOutcome dates = validDateRange(in);
        outcomes.add(dates);
        BigDecimal businessDays = null;

        if (dates.passed()) {
            List<LocalDate> workingDays = BusinessDayCalculator.workingDays(in.startDate(), in.endDate(),
                    in.holidays(), BusinessDayCalculator.DEFAULT_WEEKEND);
            businessDays = BigDecimal.valueOf(workingDays.size());
            outcomes.add(businessDaysRule(in, workingDays));
            outcomes.add(notice(in, policy));
            outcomes.add(maximumConsecutive(policy, businessDays, workingDays.size()));
            outcomes.add(!eligibility.failed() ? balance(in, businessDays) : RuleOutcome.skipped("LV-08", "SUFFICIENT_BALANCE",
                    "Skipped: employee is not eligible for the policy, so no balance applies"));
            outcomes.add(overlap(in));
            outcomes.add(documentation(in, policy, businessDays));
        } else {
            for (String[] skipped : new String[][] {
                    {"LV-05", "BUSINESS_DAYS_CALCULATED"}, {"LV-06", "MINIMUM_NOTICE"}, {"LV-07", "MAXIMUM_CONSECUTIVE_DAYS"},
                    {"LV-08", "SUFFICIENT_BALANCE"}, {"LV-09", "NO_APPROVED_OVERLAP"}, {"LV-10", "DOCUMENTATION_PROVIDED"}}) {
                outcomes.add(RuleOutcome.skipped(skipped[0], skipped[1], "Skipped: requested date range is invalid"));
            }
        }

        DecisionAggregator.Aggregate aggregate = DecisionAggregator.aggregate(outcomes);
        return new LeaveEvaluationResult(outcomes, aggregate.decision(), aggregate.reasonCodes(), businessDays);
    }

    // LV-01
    private RuleOutcome employeeActive(LeaveEvaluationInput in, Employee e) {
        boolean statusActive = e.getEmploymentStatus() == EmploymentStatus.ACTIVE;
        LocalDate end = in.endDate() == null ? in.startDate() : in.endDate();
        boolean employedThroughout = end == null || EmployeeRules.employedOn(e, end);
        if (statusActive && employedThroughout) {
            return RuleOutcome.pass("LV-01", "EMPLOYEE_ACTIVE", "Employee is active");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("employmentStatus", e.getEmploymentStatus().name());
        details.put("terminationDate", e.getTerminationDate() == null ? null : e.getTerminationDate().toString());
        return RuleOutcome.fail("LV-01", "EMPLOYEE_ACTIVE", RuleSeverity.HARD, ReasonCode.INACTIVE_EMPLOYEE,
                "Employee is not active for the requested period", details);
    }

    // LV-02
    private RuleOutcome policyEffective(LeaveEvaluationInput in, LeavePolicy policy) {
        if (in.startDate() == null || in.endDate() == null || in.endDate().isBefore(in.startDate())) {
            return RuleOutcome.skipped("LV-02", "POLICY_EFFECTIVE", "Skipped: date range is invalid");
        }
        if (policy.isEffectiveDuring(in.startDate(), in.endDate())) {
            return RuleOutcome.pass("LV-02", "POLICY_EFFECTIVE", "Policy " + policy.getLeavePolicyId() + " is active and effective");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("policyStatus", policy.getPolicyStatus().name());
        details.put("effectiveFrom", String.valueOf(policy.getEffectiveFrom()));
        details.put("effectiveTo", String.valueOf(policy.getEffectiveTo()));
        return RuleOutcome.fail("LV-02", "POLICY_EFFECTIVE", RuleSeverity.HARD, ReasonCode.POLICY_NOT_EFFECTIVE,
                "Policy is not active or not effective for the whole requested period", details);
    }

    // LV-03
    private RuleOutcome employeeEligible(LeaveEvaluationInput in, Employee e, LeavePolicy policy) {
        if (in.startDate() == null || in.endDate() == null || in.endDate().isBefore(in.startDate())) {
            return RuleOutcome.skipped("LV-03", "EMPLOYEE_ELIGIBLE", "Skipped: date range is invalid");
        }
        boolean typeOk = policy.eligibleTypes().contains(e.getEmploymentType());
        boolean countryOk = policy.getCountry() == null || policy.getCountry().equals(e.getCountry());
        LocalDate start = in.startDate();
        LocalDate end = in.endDate();
        boolean assigned = start != null && end != null && !end.isBefore(start)
                && in.assignments().stream().anyMatch(a -> a.coversRange(start, end));
        if (typeOk && countryOk && assigned) {
            return RuleOutcome.pass("LV-03", "EMPLOYEE_ELIGIBLE", "Employee is assigned to the policy");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("employmentType", e.getEmploymentType().name());
        details.put("employmentTypeEligible", typeOk);
        details.put("countryMatches", countryOk);
        details.put("effectiveAssignment", assigned);
        return RuleOutcome.fail("LV-03", "EMPLOYEE_ELIGIBLE", RuleSeverity.HARD, ReasonCode.EMPLOYEE_NOT_ELIGIBLE,
                "Employee is not eligible for leave policy " + policy.getLeavePolicyId(), details);
    }

    // LV-04
    private RuleOutcome validDateRange(LeaveEvaluationInput in) {
        LocalDate s = in.startDate();
        LocalDate e = in.endDate();
        if (s == null || e == null) {
            return RuleOutcome.fail("LV-04", "VALID_DATE_RANGE", RuleSeverity.HARD, ReasonCode.INVALID_DATE_RANGE,
                    "Start and end date are required");
        }
        if (e.isBefore(s)) {
            return RuleOutcome.fail("LV-04", "VALID_DATE_RANGE", RuleSeverity.HARD, ReasonCode.INVALID_DATE_RANGE,
                    "End date is before start date", Map.of("startDate", s.toString(), "endDate", e.toString()));
        }
        if (s.getYear() != e.getYear()) {
            return RuleOutcome.fail("LV-04", "VALID_DATE_RANGE", RuleSeverity.HARD, ReasonCode.INVALID_DATE_RANGE,
                    "A request must stay within one calendar year; split it per year",
                    Map.of("startDate", s.toString(), "endDate", e.toString()));
        }
        return RuleOutcome.pass("LV-04", "VALID_DATE_RANGE", "Date range is valid");
    }

    // LV-05
    private RuleOutcome businessDaysRule(LeaveEvaluationInput in, List<LocalDate> workingDays) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("calendarDays", ChronoUnit.DAYS.between(in.startDate(), in.endDate()) + 1);
        details.put("businessDays", workingDays.size());
        details.put("holidaysOnWeekdays", BusinessDayCalculator.holidaysOnWeekdays(in.startDate(), in.endDate(), in.holidays())
                .stream().map(LocalDate::toString).toList());
        if (workingDays.isEmpty()) {
            return RuleOutcome.fail("LV-05", "BUSINESS_DAYS_CALCULATED", RuleSeverity.HARD,
                    ReasonCode.HOLIDAY_OR_NON_WORKING_DAY_ONLY,
                    "The requested range contains only weekends or public holidays", details);
        }
        return RuleOutcome.pass("LV-05", "BUSINESS_DAYS_CALCULATED", workingDays.size() + " business day(s)", details);
    }

    // LV-06
    private RuleOutcome notice(LeaveEvaluationInput in, LeavePolicy policy) {
        long noticeDays = ChronoUnit.DAYS.between(in.submissionDate(), in.startDate());
        Map<String, Object> details = Map.of("noticeDays", noticeDays, "requiredNoticeDays", policy.getMinimumNoticeDays());
        if (noticeDays >= policy.getMinimumNoticeDays()) {
            return RuleOutcome.pass("LV-06", "MINIMUM_NOTICE", "Notice of " + noticeDays + " day(s) satisfies the policy", details);
        }
        return RuleOutcome.fail("LV-06", "MINIMUM_NOTICE", RuleSeverity.HARD, ReasonCode.INSUFFICIENT_NOTICE,
                "Policy requires " + policy.getMinimumNoticeDays() + " day(s) notice but " + noticeDays + " were given", details);
    }

    // LV-07
    private RuleOutcome maximumConsecutive(LeavePolicy policy, BigDecimal businessDays, int days) {
        Map<String, Object> details = Map.of("businessDays", days, "maximumConsecutiveDays", policy.getMaximumConsecutiveDays());
        if (days <= policy.getMaximumConsecutiveDays()) {
            return RuleOutcome.pass("LV-07", "MAXIMUM_CONSECUTIVE_DAYS", "Within the consecutive-day limit", details);
        }
        return RuleOutcome.fail("LV-07", "MAXIMUM_CONSECUTIVE_DAYS", RuleSeverity.HARD, ReasonCode.POLICY_LIMIT_EXCEEDED,
                "Request of " + days + " business days exceeds the maximum of " + policy.getMaximumConsecutiveDays(), details);
    }

    // LV-08
    private RuleOutcome balance(LeaveEvaluationInput in, BigDecimal businessDays) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestedBusinessDays", businessDays);
        details.put("availableBalance", in.availableBalance());
        if (in.availableBalance() == null) {
            details.put("noBalanceRecord", true);
            return RuleOutcome.fail("LV-08", "SUFFICIENT_BALANCE", RuleSeverity.HARD, ReasonCode.INSUFFICIENT_BALANCE,
                    "No leave balance exists for the employee, policy and year", details);
        }
        if (in.availableBalance().compareTo(businessDays) >= 0) {
            return RuleOutcome.pass("LV-08", "SUFFICIENT_BALANCE", "Available balance covers the request", details);
        }
        return RuleOutcome.fail("LV-08", "SUFFICIENT_BALANCE", RuleSeverity.HARD, ReasonCode.INSUFFICIENT_BALANCE,
                "Available balance " + in.availableBalance() + " is lower than the requested " + businessDays + " day(s)", details);
    }

    // LV-09
    private RuleOutcome overlap(LeaveEvaluationInput in) {
        if (in.approvedOverlaps().isEmpty()) {
            return RuleOutcome.pass("LV-09", "NO_APPROVED_OVERLAP", "No approved leave overlaps the requested dates");
        }
        List<String> ids = in.approvedOverlaps().stream().map(LeaveRequest::getLeaveRequestId).toList();
        return RuleOutcome.fail("LV-09", "NO_APPROVED_OVERLAP", RuleSeverity.FLAG, ReasonCode.OVERLAPPING_APPROVED_LEAVE,
                "Requested dates overlap approved leave " + String.join(", ", ids), Map.of("overlappingRequests", ids));
    }

    // LV-10
    private RuleOutcome documentation(LeaveEvaluationInput in, LeavePolicy policy, BigDecimal businessDays) {
        boolean needed = policy.isRequiresDocumentation()
                && businessDays.compareTo(BigDecimal.valueOf(policy.getDocumentationThresholdDays())) > 0;
        if (!needed) {
            return RuleOutcome.pass("LV-10", "DOCUMENTATION_PROVIDED", "No supporting documentation required");
        }
        if (in.supportingDocumentRef() != null && !in.supportingDocumentRef().isBlank()) {
            return RuleOutcome.pass("LV-10", "DOCUMENTATION_PROVIDED", "Supporting documentation provided");
        }
        return RuleOutcome.fail("LV-10", "DOCUMENTATION_PROVIDED", RuleSeverity.REVIEW, ReasonCode.DOCUMENTATION_MISSING,
                "Policy requires documentation for more than " + policy.getDocumentationThresholdDays() + " business day(s)",
                Map.of("thresholdDays", policy.getDocumentationThresholdDays()));
    }
}
