package com.hq.backend.state;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

// v_daily_states 뷰는 (user_id, run_date)가 사실상 복합 PK다 — @IdClass용 키.
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class DailyStateId implements Serializable {

    private UUID userId;
    private LocalDate runDate;
}
