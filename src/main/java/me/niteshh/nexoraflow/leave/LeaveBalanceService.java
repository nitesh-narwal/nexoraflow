package me.niteshh.nexoraflow.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import me.niteshh.nexoraflow.audit.AuditCategory;
import me.niteshh.nexoraflow.audit.AuditEventType;
import me.niteshh.nexoraflow.audit.AuditService;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.EntityType;
import me.niteshh.nexoraflow.common.Money;
import me.niteshh.nexoraflow.common.id.IdService;

/**
 * Owns every change to a leave balance. A balance is only ever modified by posting an explicit,
 * immutable ledger transaction, so the history of every adjustment is preserved.
 *
 * <p>Documented balance policy:
 * <ul>
 *   <li>available = opening + accrued + carried forward - used - pending (a database generated column);</li>
 *   <li>days are RESERVED (pending) when a request enters approval, so concurrent requests cannot
 *       over-commit the balance;</li>
 *   <li>days are CONSUMED (pending -> used) only at final approval; available balance does not change at that
 *       moment, so leave is never deducted twice;</li>
 *   <li>rejected, flagged or cancelled requests release their reservation; cancelling an approved request
 *       restores the used days.</li>
 * </ul>
 */
@Service
public class LeaveBalanceService {

    private final LeaveBalanceRepository balances;
    private final LeaveBalanceTransactionRepository transactions;
    private final IdService ids;
    private final AppClock clock;
    private final AuditService audit;

    public LeaveBalanceService(LeaveBalanceRepository balances, LeaveBalanceTransactionRepository transactions,
                               IdService ids, AppClock clock, AuditService audit) {
        this.balances = balances;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
        this.audit = audit;
    }

    public Optional<LeaveBalance> find(String employeeId, String leavePolicyId, int year) {
        return balances.findByEmployeeIdAndLeavePolicyIdAndBalanceYear(employeeId, leavePolicyId, year);
    }

    @Transactional
    public LeaveBalance createEmpty(String employeeId, String leavePolicyId, int year) {
        LeaveBalance balance = new LeaveBalance();
        balance.setLeaveBalanceId(ids.leaveBalanceId());
        balance.setEmployeeId(employeeId);
        balance.setLeavePolicyId(leavePolicyId);
        balance.setBalanceYear(year);
        balance.setOpeningBalance(Money.ZERO);
        balance.setAccruedBalance(Money.ZERO);
        balance.setCarriedForwardBalance(Money.ZERO);
        balance.setUsedBalance(Money.ZERO);
        balance.setPendingBalance(Money.ZERO);
        balance.setLastUpdatedAt(clock.now());
        return balances.save(balance);
    }

    /** Posts one ledger transaction and applies its deltas to the balance. */
    @Transactional
    public LeaveBalanceTransaction post(LeaveBalance balance, BalanceTransactionType type, String leaveRequestId,
                                        String periodKey, BigDecimal opening, BigDecimal accrued, BigDecimal carried,
                                        BigDecimal used, BigDecimal pending, LocalDate txnDate, String actor, String reason) {
        BigDecimal newUsed = balance.getUsedBalance().add(used);
        BigDecimal newPending = balance.getPendingBalance().add(pending);
        if (newUsed.signum() < 0 || newPending.signum() < 0) {
            throw new IllegalStateException("Balance transaction " + type + " would make used/pending negative for "
                    + balance.getLeaveBalanceId());
        }
        balance.setOpeningBalance(balance.getOpeningBalance().add(opening));
        balance.setAccruedBalance(balance.getAccruedBalance().add(accrued));
        balance.setCarriedForwardBalance(balance.getCarriedForwardBalance().add(carried));
        balance.setUsedBalance(newUsed);
        balance.setPendingBalance(newPending);
        balance.setLastUpdatedAt(clock.now());
        balances.save(balance);

        LeaveBalanceTransaction txn = new LeaveBalanceTransaction();
        txn.setTxnId(ids.leaveBalanceTxnId());
        txn.setLeaveBalanceId(balance.getLeaveBalanceId());
        txn.setLeaveRequestId(leaveRequestId);
        txn.setTxnType(type);
        txn.setPeriodKey(periodKey);
        txn.setOpeningDelta(Money.scale(opening));
        txn.setAccruedDelta(Money.scale(accrued));
        txn.setCarriedForwardDelta(Money.scale(carried));
        txn.setUsedDelta(Money.scale(used));
        txn.setPendingDelta(Money.scale(pending));
        txn.setTxnDate(txnDate);
        txn.setOccurredAt(clock.now());
        txn.setActor(actor);
        txn.setReason(reason);
        return transactions.save(txn);
    }

    /** Reserves the request's business days (pending). Called when the request enters approval. */
    @Transactional
    public void reserve(LeaveRequest request, String actor) {
        LeaveBalance balance = requireBalance(request);
        BigDecimal days = days(request);
        post(balance, BalanceTransactionType.PENDING_RESERVE, request.getLeaveRequestId(), null,
                Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, days, clock.today(), actor,
                "Reserve " + days + " day(s) for " + request.getLeaveRequestId());
        auditBalance(balance, AuditEventType.BALANCE_RESERVED, request, days, actor);
    }

    /** Releases a reservation (rejected, cancelled while pending, or flagged at final approval). */
    @Transactional
    public void release(LeaveRequest request, String actor, String reason) {
        if (netReserved(request).signum() <= 0) {
            return; // nothing reserved: releasing twice is a no-op, never a double credit
        }
        LeaveBalance balance = requireBalance(request);
        BigDecimal days = days(request);
        post(balance, BalanceTransactionType.PENDING_RELEASE, request.getLeaveRequestId(), null,
                Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, days.negate(), clock.today(), actor, reason);
        auditBalance(balance, AuditEventType.BALANCE_RELEASED, request, days, actor);
    }

    /** Final approval: pending days become used days exactly once. */
    @Transactional
    public void consume(LeaveRequest request, String actor) {
        if (netReserved(request).signum() <= 0) {
            throw new IllegalStateException("Cannot consume leave for " + request.getLeaveRequestId() + ": no reservation");
        }
        LeaveBalance balance = requireBalance(request);
        BigDecimal days = days(request);
        post(balance, BalanceTransactionType.CONSUME, request.getLeaveRequestId(), null,
                Money.ZERO, Money.ZERO, Money.ZERO, days, days.negate(), clock.today(), actor,
                "Final approval of " + request.getLeaveRequestId());
        auditBalance(balance, AuditEventType.BALANCE_CONSUMED, request, days, actor);
    }

    /** Cancelling an approved request gives its used days back. */
    @Transactional
    public void restore(LeaveRequest request, String actor, String reason) {
        LeaveBalance balance = requireBalance(request);
        BigDecimal days = days(request);
        post(balance, BalanceTransactionType.RESTORE, request.getLeaveRequestId(), null,
                Money.ZERO, Money.ZERO, Money.ZERO, days.negate(), Money.ZERO, clock.today(), actor, reason);
        auditBalance(balance, AuditEventType.BALANCE_RESTORED, request, days, actor);
    }

    /** Reserved-but-not-yet-settled days of a request, derived from the ledger. */
    public BigDecimal netReserved(LeaveRequest request) {
        BigDecimal net = Money.ZERO;
        for (LeaveBalanceTransaction t : transactions.findByLeaveRequestIdOrderByOccurredAtAscTxnIdAsc(request.getLeaveRequestId())) {
            net = net.add(t.getPendingDelta());
        }
        return net;
    }

    public List<LeaveBalanceTransaction> history(String leaveBalanceId) {
        return transactions.findByLeaveBalanceIdOrderByOccurredAtAscTxnIdAsc(leaveBalanceId);
    }

    private LeaveBalance requireBalance(LeaveRequest request) {
        int year = request.getStartDate().getYear();
        return find(request.getEmployeeId(), request.getLeavePolicyId(), year)
                .orElseThrow(() -> new IllegalStateException("No leave balance for " + request.getEmployeeId()
                        + "/" + request.getLeavePolicyId() + "/" + year));
    }

    private static BigDecimal days(LeaveRequest request) {
        return Money.scale(request.getCalculatedBusinessDays());
    }

    private void auditBalance(LeaveBalance balance, String eventType, LeaveRequest request, BigDecimal days, String actor) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("leaveRequestId", request.getLeaveRequestId());
        meta.put("days", days);
        meta.put("available", balance.computeAvailable());
        meta.put("used", balance.getUsedBalance());
        meta.put("pending", balance.getPendingBalance());
        audit.record(AuditCategory.BALANCE, EntityType.LEAVE_BALANCE, balance.getLeaveBalanceId(), eventType,
                null, null, actor, null, meta);
    }

    static int yearOf(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).getYear();
    }
}
