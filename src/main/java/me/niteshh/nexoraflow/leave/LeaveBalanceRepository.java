package me.niteshh.nexoraflow.leave;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, String> {

    Optional<LeaveBalance> findByEmployeeIdAndLeavePolicyIdAndBalanceYear(String employeeId, String leavePolicyId, int balanceYear);

    List<LeaveBalance> findByEmployeeIdOrderByBalanceYearDescLeavePolicyId(String employeeId);

    List<LeaveBalance> findByBalanceYearOrderByLeaveBalanceId(int balanceYear);
}
