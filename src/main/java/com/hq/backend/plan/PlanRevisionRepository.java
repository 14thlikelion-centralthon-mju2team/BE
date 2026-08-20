package com.hq.backend.plan;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlanRevisionRepository extends JpaRepository<PlanRevision, UUID> {

    Optional<PlanRevision> findByEventIdAndPlanStatus(UUID eventId, String planStatus);

    /**
     * active revision의 상태 전이(재계산)를 직렬화한다.
     * PostgreSQL에서는 SELECT ... FOR UPDATE로 실행되어 active plan을 둘이 동시에
     * supersede하고 새 revision을 INSERT하는 경쟁을 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p FROM PlanRevision p
            WHERE p.eventId = :eventId AND p.planStatus = 'active'
            """)
    Optional<PlanRevision> findActiveByEventIdForUpdate(@Param("eventId") UUID eventId);

    /** active plan 수정·경로 선택 전에 해당 revision 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PlanRevision p WHERE p.planId = :planId")
    Optional<PlanRevision> findByIdForUpdate(@Param("planId") UUID planId);

    List<PlanRevision> findByEventIdOrderByRevisionNoDesc(UUID eventId);

    List<PlanRevision> findByEventIdIn(List<UUID> eventIds);
}
