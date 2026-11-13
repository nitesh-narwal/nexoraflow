package me.niteshh.nexoraflow.budget;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetRepository extends JpaRepository<Budget, String>, JpaSpecificationExecutor<Budget> {

    @Query("""
            select b from Budget b
            where b.costCenterId = :costCenterId and b.periodStart <= :date and b.periodEnd >= :date
            """)
    Optional<Budget> findForDate(@Param("costCenterId") String costCenterId, @Param("date") LocalDate date);

    List<Budget> findByCostCenterIdOrderByPeriodStart(String costCenterId);
}
