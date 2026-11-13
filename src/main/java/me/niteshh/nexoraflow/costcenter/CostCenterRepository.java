package me.niteshh.nexoraflow.costcenter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CostCenterRepository extends JpaRepository<CostCenter, String>, JpaSpecificationExecutor<CostCenter> {
}
