package me.niteshh.nexoraflow.leave;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveBalanceTransactionRepository extends JpaRepository<LeaveBalanceTransaction, String> {

    List<LeaveBalanceTransaction> findByLeaveBalanceIdOrderByOccurredAtAscTxnIdAsc(String leaveBalanceId);

    List<LeaveBalanceTransaction> findByLeaveRequestIdOrderByOccurredAtAscTxnIdAsc(String leaveRequestId);

    boolean existsByLeaveBalanceIdAndPeriodKeyAndTxnType(String leaveBalanceId, String periodKey, BalanceTransactionType txnType);
}
