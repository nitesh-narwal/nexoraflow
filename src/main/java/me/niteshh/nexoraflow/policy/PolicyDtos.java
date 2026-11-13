package me.niteshh.nexoraflow.policy;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class PolicyDtos {

    private PolicyDtos() {
    }

    public record LeavePolicyResponse(String leavePolicyId, String policyName, LeaveType leaveType, String country,
                                      BigDecimal annualEntitlement, AccrualFrequency accrualFrequency,
                                      BigDecimal carryForwardLimit, int maximumConsecutiveDays, int minimumNoticeDays,
                                      boolean requiresDocumentation, int documentationThresholdDays,
                                      String eligibleEmploymentTypes, LocalDate effectiveFrom, LocalDate effectiveTo,
                                      PolicyStatus policyStatus) {
        public static LeavePolicyResponse from(LeavePolicy p) {
            return new LeavePolicyResponse(p.getLeavePolicyId(), p.getPolicyName(), p.getLeaveType(), p.getCountry(),
                    p.getAnnualEntitlement(), p.getAccrualFrequency(), p.getCarryForwardLimit(), p.getMaximumConsecutiveDays(),
                    p.getMinimumNoticeDays(), p.isRequiresDocumentation(), p.getDocumentationThresholdDays(),
                    p.getEligibleEmploymentTypes(), p.getEffectiveFrom(), p.getEffectiveTo(), p.getPolicyStatus());
        }
    }

    public record ExpensePolicyResponse(String expensePolicyId, String policyName, ExpenseCategory expenseCategory,
                                        String country, PolicyEmployeeType employeeType, String currency,
                                        BigDecimal maximumPerTransaction, BigDecimal maximumDailyAmount,
                                        BigDecimal maximumMonthlyAmount, boolean receiptRequired, BigDecimal receiptThreshold,
                                        boolean requiresManagerApproval, LocalDate effectiveFrom, LocalDate effectiveTo,
                                        PolicyStatus policyStatus) {
        public static ExpensePolicyResponse from(ExpensePolicy p) {
            return new ExpensePolicyResponse(p.getExpensePolicyId(), p.getPolicyName(), p.getExpenseCategory(), p.getCountry(),
                    p.getEmployeeType(), p.getCurrency(), p.getMaximumPerTransaction(), p.getMaximumDailyAmount(),
                    p.getMaximumMonthlyAmount(), p.isReceiptRequired(), p.getReceiptThreshold(), p.isRequiresManagerApproval(),
                    p.getEffectiveFrom(), p.getEffectiveTo(), p.getPolicyStatus());
        }
    }
}
