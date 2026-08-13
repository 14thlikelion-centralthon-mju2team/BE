package com.hq.backend.gapcheck;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GapCheckRepository extends JpaRepository<GapCheck, Long> {
    boolean existsByUserIdAndLogDate(UUID userId, LocalDate logDate);
}
