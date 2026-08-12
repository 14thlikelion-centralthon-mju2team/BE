package com.hq.backend.state;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyStateRepository extends JpaRepository<DailyState, DailyStateId> {

    List<DailyState> findByUserIdAndRunDateBetweenOrderByRunDateAsc(UUID userId, LocalDate from, LocalDate to);
}
