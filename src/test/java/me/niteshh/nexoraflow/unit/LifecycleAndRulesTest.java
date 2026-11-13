package me.niteshh.nexoraflow.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import me.niteshh.nexoraflow.approval.ApprovalStatus;
import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.rules.DecisionAggregator;
import me.niteshh.nexoraflow.common.rules.RuleOutcome;
import me.niteshh.nexoraflow.common.rules.RuleSeverity;
import me.niteshh.nexoraflow.expense.ExpenseClaimStatus;
import me.niteshh.nexoraflow.leave.LeaveRequestStatus;

class LifecycleAndRulesTest {

    @Test
    void leaveRequestsFollowTheDocumentedLifecycle() {
        var l = LeaveRequestStatus.LIFECYCLE;
        assertThat(l.canTransition(LeaveRequestStatus.SUBMITTED, LeaveRequestStatus.PENDING_APPROVAL)).isTrue();
        assertThat(l.canTransition(LeaveRequestStatus.PENDING_APPROVAL, LeaveRequestStatus.APPROVED)).isTrue();
        assertThat(l.canTransition(LeaveRequestStatus.APPROVED, LeaveRequestStatus.CANCELLED)).isTrue();
        // invalid transitions
        assertThat(l.canTransition(LeaveRequestStatus.SUBMITTED, LeaveRequestStatus.APPROVED)).isFalse();   // approval needs the workflow
        assertThat(l.canTransition(LeaveRequestStatus.REJECTED, LeaveRequestStatus.APPROVED)).isFalse();
        assertThat(l.canTransition(LeaveRequestStatus.CANCELLED, LeaveRequestStatus.PENDING_APPROVAL)).isFalse();
        assertThat(l.canTransition(LeaveRequestStatus.APPROVED, LeaveRequestStatus.REJECTED)).isFalse();
        assertThat(LeaveRequestStatus.REJECTED.isTerminal()).isTrue();
        assertThat(LeaveRequestStatus.APPROVED.isTerminal()).isFalse(); // can still be cancelled
    }

    @Test
    void expenseClaimsFollowTheDocumentedLifecycle() {
        var l = ExpenseClaimStatus.LIFECYCLE;
        assertThat(l.canTransition(ExpenseClaimStatus.SUBMITTED, ExpenseClaimStatus.ON_HOLD)).isTrue();
        assertThat(l.canTransition(ExpenseClaimStatus.ON_HOLD, ExpenseClaimStatus.PENDING_APPROVAL)).isTrue();
        assertThat(l.canTransition(ExpenseClaimStatus.PENDING_APPROVAL, ExpenseClaimStatus.APPROVED)).isTrue();
        assertThat(l.canTransition(ExpenseClaimStatus.SUBMITTED, ExpenseClaimStatus.APPROVED)).isFalse();
        assertThat(l.canTransition(ExpenseClaimStatus.APPROVED, ExpenseClaimStatus.CANCELLED)).isFalse();
        assertThat(l.canTransition(ExpenseClaimStatus.REJECTED, ExpenseClaimStatus.PENDING_APPROVAL)).isFalse();
        assertThat(l.isTerminal(ExpenseClaimStatus.APPROVED)).isTrue();
    }

    @Test
    void approvalStepsAreImmutableOnceComplete() {
        var l = ApprovalStatus.LIFECYCLE;
        assertThat(l.canTransition(ApprovalStatus.WAITING, ApprovalStatus.PENDING)).isTrue();
        assertThat(l.canTransition(ApprovalStatus.PENDING, ApprovalStatus.APPROVED)).isTrue();
        assertThat(l.canTransition(ApprovalStatus.PENDING, ApprovalStatus.ESCALATED)).isTrue();
        assertThat(l.canTransition(ApprovalStatus.WAITING, ApprovalStatus.APPROVED)).isFalse();      // cannot skip activation
        for (ApprovalStatus done : List.of(ApprovalStatus.APPROVED, ApprovalStatus.REJECTED, ApprovalStatus.CANCELLED, ApprovalStatus.ESCALATED, ApprovalStatus.SKIPPED)) {
            assertThat(done.isTerminal()).as(done.name()).isTrue();
            assertThat(l.allowedFrom(done)).isEmpty();
        }
    }

    private static RuleOutcome fail(RuleSeverity severity, ReasonCode code) {
        return RuleOutcome.fail("X", "X", severity, code, "m");
    }

    @Test
    void theStrongestFailedRuleDeterminesTheDecision() {
        assertThat(DecisionAggregator.aggregate(List.of(RuleOutcome.pass("A", "A", "ok"))).decision()).isEqualTo(Decision.PENDING_APPROVAL);
        assertThat(DecisionAggregator.aggregate(List.of(fail(RuleSeverity.REVIEW, ReasonCode.DOCUMENTATION_MISSING))).decision()).isEqualTo(Decision.NEEDS_REVIEW);
        assertThat(DecisionAggregator.aggregate(List.of(fail(RuleSeverity.REVIEW, ReasonCode.DOCUMENTATION_MISSING),
                fail(RuleSeverity.FLAG, ReasonCode.RECEIPT_MISSING))).decision()).isEqualTo(Decision.FLAGGED);
        assertThat(DecisionAggregator.aggregate(List.of(fail(RuleSeverity.FLAG, ReasonCode.RECEIPT_MISSING),
                fail(RuleSeverity.HOLD, ReasonCode.INSUFFICIENT_BUDGET))).decision()).isEqualTo(Decision.ON_HOLD);
        var rejected = DecisionAggregator.aggregate(List.of(fail(RuleSeverity.HOLD, ReasonCode.INSUFFICIENT_BUDGET),
                fail(RuleSeverity.HARD, ReasonCode.POLICY_LIMIT_EXCEEDED), fail(RuleSeverity.FLAG, ReasonCode.RECEIPT_MISSING)));
        assertThat(rejected.decision()).isEqualTo(Decision.REJECTED);
        assertThat(rejected.reasonCodes()).containsExactly(ReasonCode.INSUFFICIENT_BUDGET, ReasonCode.POLICY_LIMIT_EXCEEDED, ReasonCode.RECEIPT_MISSING);
    }

    @Test
    void skippedAndPassedRulesNeverAffectTheDecision() {
        var agg = DecisionAggregator.aggregate(List.of(RuleOutcome.skipped("A", "A", "n/a"), RuleOutcome.pass("B", "B", "ok")));
        assertThat(agg.decision()).isEqualTo(Decision.PENDING_APPROVAL);
        assertThat(agg.reasonCodesAsText()).isEmpty();
    }

    @Test
    void duplicateReasonCodesAreReportedOnce() {
        var agg = DecisionAggregator.aggregate(List.of(fail(RuleSeverity.HARD, ReasonCode.POLICY_LIMIT_EXCEEDED), fail(RuleSeverity.HARD, ReasonCode.POLICY_LIMIT_EXCEEDED)));
        assertThat(agg.reasonCodes()).containsExactly(ReasonCode.POLICY_LIMIT_EXCEEDED);
        assertThat(agg.reasonCodesAsText()).isEqualTo("POLICY_LIMIT_EXCEEDED");
        assertThat(new BigDecimal("1.0")).isEqualByComparingTo(BigDecimal.ONE); // BigDecimal comparisons ignore scale
    }
}
