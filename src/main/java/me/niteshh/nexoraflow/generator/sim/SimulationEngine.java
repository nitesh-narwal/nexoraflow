package me.niteshh.nexoraflow.generator.sim;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import me.niteshh.nexoraflow.approval.ApprovalService;
import me.niteshh.nexoraflow.approval.ApprovalStatus;
import me.niteshh.nexoraflow.approval.ApprovalStep;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.expense.ExpenseSubmissionResult;
import me.niteshh.nexoraflow.expense.ExpenseWorkflowService;
import me.niteshh.nexoraflow.generator.GeneratorSettings;
import me.niteshh.nexoraflow.generator.plan.Plans.ClaimPlan;
import me.niteshh.nexoraflow.generator.plan.Plans.LeavePlan;
import me.niteshh.nexoraflow.generator.plan.Plans.Plan;
import me.niteshh.nexoraflow.generator.plan.Plans.SideAction;
import me.niteshh.nexoraflow.generator.plan.Plans.TimedDecision;
import me.niteshh.nexoraflow.generator.plan.Plans.Verdict;
import me.niteshh.nexoraflow.leave.LeaveAccrualService;
import me.niteshh.nexoraflow.leave.LeaveRequestStatus;
import me.niteshh.nexoraflow.leave.LeaveSubmissionResult;
import me.niteshh.nexoraflow.leave.LeaveWorkflowService;
import me.niteshh.nexoraflow.reimbursement.ReimbursementRepository;
import me.niteshh.nexoraflow.reimbursement.ReimbursementService;

/**
 * Discrete-event simulation of the company: submissions, evaluations, approver decisions, cancellations, payroll
 * runs and monthly accruals happen in strict chronological order against the REAL workflow services. The
 * application clock is pinned to each event's simulated time, so every stored timestamp is a function of the seed.
 * Single-threaded; ties are broken by creation order. Events after the cutoff never execute (requests stay pending).
 */
@Service
public class SimulationEngine {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    public record Stats(int plans, int eventsExecuted, int eventsBeyondCutoff, int intakeRejected, int decisionsApplied,
                        int decisionsWithoutPendingStep) {
    }

    private record Event(Instant at, long seq, Runnable action) {
    }

    private final LeaveWorkflowService leave;
    private final ExpenseWorkflowService expenses;
    private final ApprovalService approvals;
    private final ReimbursementService reimbursements;
    private final ReimbursementRepository reimbursementRepository;
    private final LeaveAccrualService accruals;

    // per-run state (the engine is used by one generation at a time)
    private PriorityQueue<Event> queue;
    private long sequence;
    private Instant cutoff;
    private int executed;
    private int beyond;
    private int intakeRejected;
    private int applied;
    private int noPending;

    public SimulationEngine(LeaveWorkflowService leave, ExpenseWorkflowService expenses, ApprovalService approvals,
                            ReimbursementService reimbursements, ReimbursementRepository reimbursementRepository,
                            LeaveAccrualService accruals) {
        this.leave = leave;
        this.expenses = expenses;
        this.approvals = approvals;
        this.reimbursements = reimbursements;
        this.reimbursementRepository = reimbursementRepository;
        this.accruals = accruals;
    }

    public synchronized Stats run(List<? extends Plan> plans, GeneratorSettings settings, boolean postAccruals) {
        queue = new PriorityQueue<>(Comparator.comparing(Event::at).thenComparingLong(Event::seq));
        sequence = 0;
        cutoff = settings.cutoff();
        executed = beyond = intakeRejected = applied = noPending = 0;

        if (postAccruals) {
            for (int month = 1; month <= settings.referenceDate().getMonthValue(); month++) {
                YearMonth ym = YearMonth.of(settings.year(), month);
                schedule(ym.atDay(1).atTime(0, 5).toInstant(java.time.ZoneOffset.UTC), () -> accruals.accrueMonth(ym, "SYSTEM"));
            }
        }
        for (Plan plan : plans) {
            schedule(plan.submitAt, () -> submit(plan));
        }
        try {
            while (!queue.isEmpty()) {
                Event event = queue.poll();
                AppClock.setSimulatedTime(event.at());
                event.action().run();
                executed++;
            }
        } finally {
            AppClock.clearSimulatedTime();
        }
        log.info("Simulation finished: {} plans, {} events, {} beyond cutoff", plans.size(), executed, beyond);
        return new Stats(plans.size(), executed, beyond, intakeRejected, applied, noPending);
    }

    private void schedule(Instant at, Runnable action) {
        if (at.isAfter(cutoff)) {
            beyond++;
            return;
        }
        queue.add(new Event(at, sequence++, action));
    }

    // ------------------------------------------------------------------ submission

    private void submit(Plan plan) {
        String requestId;
        boolean needsEvaluation;
        if (plan instanceof LeavePlan lp) {
            LeaveSubmissionResult r = leave.submit(lp.submission);
            plan.submissionId = r.submissionId();
            if (!r.accepted()) {
                plan.truthOnSubmission = true;
                intakeRejected++;
                return;
            }
            requestId = r.request().getLeaveRequestId();
            needsEvaluation = r.request().getRequestStatus() == LeaveRequestStatus.SUBMITTED;
        } else if (plan instanceof ClaimPlan cp) {
            ExpenseSubmissionResult r = expenses.submit(cp.submission);
            plan.submissionId = r.submissionId();
            if (!r.accepted()) {
                plan.truthOnSubmission = true;
                intakeRejected++;
                return;
            }
            requestId = r.claim().getExpenseClaimId();
            needsEvaluation = r.claim().getClaimStatus() == me.niteshh.nexoraflow.expense.ExpenseClaimStatus.SUBMITTED;
        } else {
            throw new IllegalArgumentException("Unknown plan type " + plan.getClass());
        }
        plan.entityId = requestId;
        Instant now = AppClock.currentInstant();
        if (needsEvaluation) {
            schedule(now.plusSeconds(120), () -> evaluate(plan));
        }
        for (TimedDecision d : plan.decisions) {
            schedule(d.at(), () -> decide(plan, d.verdict()));
        }
        for (SideAction s : plan.sideActions) {
            schedule(s.at(), () -> sideAction(plan, s));
        }
    }

    private void evaluate(Plan plan) {
        if (plan instanceof LeavePlan) {
            leave.evaluate(plan.entityId, plan.requester);
        } else {
            expenses.evaluate(plan.entityId, plan.requester);
        }
    }

    // ------------------------------------------------------------------ human behaviour

    private List<ApprovalStep> stepsOf(Plan plan) {
        return approvals.stepsOf(plan instanceof LeavePlan ? RequestType.LEAVE : RequestType.EXPENSE, plan.entityId);
    }

    private Optional<ApprovalStep> pendingStep(Plan plan) {
        RequestType type = plan instanceof LeavePlan ? RequestType.LEAVE : RequestType.EXPENSE;
        return approvals.stepsOf(type, plan.entityId).stream().filter(s -> s.getApprovalStatus() == ApprovalStatus.PENDING).findFirst();
    }

    private void decide(Plan plan, Verdict verdict) {
        Optional<ApprovalStep> step = pendingStep(plan);
        if (step.isEmpty()) {
            noPending++;
            return;
        }
        ApprovalService.ActionCommand cmd = new ApprovalService.ActionCommand(step.get().getApproverEmployeeId(),
                "scripted approver decision", verdict == Verdict.APPROVE ? "Approved" : "Rejected after review");
        if (verdict == Verdict.APPROVE) {
            approvals.approve(step.get().getApprovalStepId(), cmd);
        } else {
            approvals.reject(step.get().getApprovalStepId(), cmd);
        }
        applied++;
    }

    private void sideAction(Plan plan, SideAction action) {
        switch (action.kind()) {
            case CANCEL -> {
                if (plan instanceof LeavePlan) {
                    leave.cancel(plan.entityId, action.actor(), "Plans changed");
                } else {
                    expenses.cancel(plan.entityId, action.actor(), "Withdrawn by requester");
                }
            }
            case SELF_APPROVE_ATTEMPT, UNAUTHORIZED_APPROVE_ATTEMPT -> pendingStep(plan).ifPresent(step ->
                    approvals.approve(step.getApprovalStepId(), new ApprovalService.ActionCommand(action.actor(), "attempt", "attempt")));
            case REIMBURSE_RETRY -> reimbursements.requestReimbursement(plan.entityId, action.actor());
            case MARK_PAID -> reimbursementRepository.findByExpenseClaimId(plan.entityId)
                    .ifPresent(r -> reimbursements.markPaid(r.getReimbursementId(), action.actor()));
            case OUT_OF_ORDER_APPROVE -> stepsOf(plan).stream().filter(st -> st.getApprovalStatus() == ApprovalStatus.WAITING).findFirst()
                    .ifPresent(st -> approvals.approve(st.getApprovalStepId(),
                            new ApprovalService.ActionCommand(st.getApproverEmployeeId(), "too early", "attempt")));
            case REPEAT_APPROVE -> stepsOf(plan).stream().filter(st -> st.getApprovalStatus() == ApprovalStatus.APPROVED).findFirst()
                    .ifPresent(st -> approvals.approve(st.getApprovalStepId(),
                            new ApprovalService.ActionCommand(st.getApproverEmployeeId(), "again", "attempt")));
        }
    }
}
