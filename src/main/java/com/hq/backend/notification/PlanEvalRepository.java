package com.hq.backend.notification;

import com.hq.backend.plan.PlanRevision;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.UUID;

/**
 * 오케스트레이터 전용 — next_eval_at 기반 계획 폴링.
 * FOR UPDATE SKIP LOCKED로 워커가 늘어도 같은 계획을 두 번 처리하지 않는다 (TRD §13.2).
 */
public interface PlanEvalRepository extends JpaRepository<PlanRevision, UUID> {

    @Query(value = """
            SELECT * FROM plan_revision
            WHERE next_eval_at <= :now
              AND plan_status = 'active'
            ORDER BY next_eval_at
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
            """, nativeQuery = true)
    List<PlanRevision> findDueForEvaluation(Instant now, int limit);
}
