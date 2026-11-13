package me.niteshh.nexoraflow.leave;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, String>, JpaSpecificationExecutor<LeaveRequest> {

    /** Other requests of the employee in the given statuses whose date range intersects [start, end]. */
    @Query("""
            select r from LeaveRequest r
            where r.employeeId = :employeeId
              and r.leaveRequestId <> :excludeId
              and r.requestStatus in :statuses
              and r.startDate <= :end and r.endDate >= :start
            order by r.startDate, r.leaveRequestId
            """)
    List<LeaveRequest> findOverlapping(@Param("employeeId") String employeeId,
                                       @Param("excludeId") String excludeId,
                                       @Param("statuses") Collection<LeaveRequestStatus> statuses,
                                       @Param("start") LocalDate start,
                                       @Param("end") LocalDate end);

    List<LeaveRequest> findByEmployeeIdOrderByStartDate(String employeeId);
}
