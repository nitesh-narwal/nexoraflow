package me.niteshh.nexoraflow.expense;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.employee.EmploymentType;
import me.niteshh.nexoraflow.policy.ExpenseCategory;
import me.niteshh.nexoraflow.policy.ExpensePolicy;
import me.niteshh.nexoraflow.policy.ExpensePolicyRepository;
import me.niteshh.nexoraflow.policy.PolicyEmployeeType;

/**
 * Finds the expense policy that governs a transaction. Effective dates are always honoured: a policy is
 * only applicable on the transaction date when it is ACTIVE and its effective window covers that date.
 * A policy written for the specific employee type beats an ALL-types policy; then the most recent version wins.
 */
@Component
public class ExpensePolicyResolver {

    private final ExpensePolicyRepository policies;

    public ExpensePolicyResolver(ExpensePolicyRepository policies) {
        this.policies = policies;
    }

    public Optional<ExpensePolicy> resolve(ExpenseCategory category, String country, EmploymentType type, LocalDate date) {
        return select(policies.findByExpenseCategoryAndCountryOrderByEffectiveFromDesc(category, country), type, date);
    }

    /** True when any version of a matching policy exists, whatever its dates or status. */
    public boolean existsAnyVersion(ExpenseCategory category, String country, EmploymentType type) {
        return policies.findByExpenseCategoryAndCountryOrderByEffectiveFromDesc(category, country).stream()
                .anyMatch(p -> p.getEmployeeType().appliesTo(type));
    }

    /** Pure selection logic. */
    public static Optional<ExpensePolicy> select(List<ExpensePolicy> candidates, EmploymentType type, LocalDate date) {
        return candidates.stream()
                .filter(p -> p.isEffectiveOn(date))
                .filter(p -> p.getEmployeeType().appliesTo(type))
                .min(Comparator
                        .comparing((ExpensePolicy p) -> p.getEmployeeType() == PolicyEmployeeType.ALL)
                        .thenComparing(ExpensePolicy::getEffectiveFrom, Comparator.reverseOrder())
                        .thenComparing(ExpensePolicy::getExpensePolicyId));
    }
}
