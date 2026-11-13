package me.niteshh.nexoraflow.generator.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import me.niteshh.nexoraflow.expense.ExpenseSubmission;
import me.niteshh.nexoraflow.leave.LeaveSubmission;

/**
 * A plan is an ordinary submission plus the scripted behaviour of the humans around it (approver decisions,
 * cancellations, payroll runs) and the ground-truth label derived from the SCENARIO CONFIGURATION. Plans are
 * produced from documented rules and construction - never by calling the decision engine.
 */
public final class Plans {

    private Plans() {
    }

    public enum Verdict { APPROVE, REJECT }

    /** A decision the approver of the currently pending step takes at an absolute simulated time. */
    public record TimedDecision(Instant at, Verdict verdict) {
    }

    public enum SideKind {
        /** Requester cancels the request. */
        CANCEL,
        /** The requester tries to approve their own request. */
        SELF_APPROVE_ATTEMPT,
        /** An unrelated employee without authority tries to approve. */
        UNAUTHORIZED_APPROVE_ATTEMPT,
        /** A payroll run asks for the reimbursement again. */
        REIMBURSE_RETRY,
        /** Payroll pays the reimbursement. */
        MARK_PAID,
        /** An approver tries to approve a step that is still WAITING (earlier level not complete). */
        OUT_OF_ORDER_APPROVE,
        /** An approver tries to approve a step that is already completed (overwrite attempt). */
        REPEAT_APPROVE
    }

    public record SideAction(Instant at, SideKind kind, String actor) {
    }

    /** What the ground truth says about one entity. Entity IDs are bound after the simulation assigned them. */
    public record TruthSpec(
            String scenarioId,
            String issueType,
            boolean anomaly,
            String expectedOutcome,
            String expectedReasonCode,
            String expectedApprovalPath,
            String expectedReimbursementStatus,
            String severity,
            String explanation,
            String evidenceType,
            List<String> relatedPlanKeys,
            String ruleIds) {
    }

    public abstract static class Plan {
        public final String key;
        public final String requester;
        public Instant submitAt;
        public final List<TimedDecision> decisions = new ArrayList<>();
        public final List<SideAction> sideActions = new ArrayList<>();
        public TruthSpec truth;
        /** Additional labels for the same entity (e.g. an attempted violation on an otherwise valid request). */
        public final List<TruthSpec> extraTruths = new ArrayList<>();
        /** True when the label refers to the staging submission (rejected at intake) instead of a canonical request. */
        public boolean truthOnSubmission;
        // filled by the simulation
        public String entityId;
        public String submissionId;

        protected Plan(String key, String requester, Instant submitAt) {
            this.key = key;
            this.requester = requester;
            this.submitAt = submitAt;
        }
    }

    public static final class LeavePlan extends Plan {
        public final LeaveSubmission submission;

        public LeavePlan(String key, String requester, Instant submitAt, LeaveSubmission submission) {
            super(key, requester, submitAt);
            this.submission = submission;
        }
    }

    public static final class ClaimPlan extends Plan {
        public final ExpenseSubmission submission;

        public ClaimPlan(String key, String requester, Instant submitAt, ExpenseSubmission submission) {
            super(key, requester, submitAt);
            this.submission = submission;
        }
    }
}
