package com.hq.backend.routine;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineTaskRepository extends JpaRepository<RoutineTask, UUID> {

    List<RoutineTask> findByRoutineIdAndArchivedAtIsNullOrderByOrderNo(UUID routineId);

    List<RoutineTask> findByRoutineIdInAndArchivedAtIsNull(List<UUID> routineIds);
}
