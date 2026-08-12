package com.hq.backend.checkin;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckinRepository extends JpaRepository<Checkin, Long> {
    boolean existsByUserIdAndLogDate(UUID userId, LocalDate logDate);
}
