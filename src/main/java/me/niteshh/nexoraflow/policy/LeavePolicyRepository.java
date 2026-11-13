package me.niteshh.nexoraflow.policy;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface LeavePolicyRepository extends JpaRepository<LeavePolicy, String>, JpaSpecificationExecutor<LeavePolicy> {

    List<LeavePolicy> findAllByOrderByLeavePolicyId();
}
