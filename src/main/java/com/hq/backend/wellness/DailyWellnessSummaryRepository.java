package com.hq.backend.wellness;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyWellnessSummaryRepository extends JpaRepository<DailyWellnessSummary, UUID> {

    Optional<DailyWellnessSummary> findByUserIdAndSummaryDate(UUID userId, LocalDate summaryDate);

    Optional<DailyWellnessSummary> findBySummaryIdAndUserId(UUID summaryId, UUID userId);
}
