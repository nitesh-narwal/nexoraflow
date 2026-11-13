package me.niteshh.nexoraflow.policy;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ExpensePolicyRepository extends JpaRepository<ExpensePolicy, String>, JpaSpecificationExecutor<ExpensePolicy> {

    /** Every version (any status / period) of the policies for a category and country. */
    List<ExpensePolicy> findByExpenseCategoryAndCountryOrderByEffectiveFromDesc(ExpenseCategory category, String country);

    List<ExpensePolicy> findAllByOrderByExpensePolicyId();
}
