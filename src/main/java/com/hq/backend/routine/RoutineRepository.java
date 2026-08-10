package com.hq.backend.routine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineRepository extends JpaRepository<Routine, UUID> {

    List<Routine> findByUserIdAndArchivedAtIsNull(UUID userId);

    Optional<Routine> findByIdAndUserId(UUID id, UUID userId);
}
