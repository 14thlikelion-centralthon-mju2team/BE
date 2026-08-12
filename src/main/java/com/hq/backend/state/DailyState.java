package com.hq.backend.state;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

// v_daily_states는 저장하지 않고 매 요청 계산되는 뷰(V1__init.sql) — 조회 전용, save() 호출 없음.
@Entity
@Immutable
@Table(name = "v_daily_states")
@IdClass(DailyStateId.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyState {

    @Id
    private UUID userId;

    @Id
    private LocalDate runDate;

    private Integer doneCount;

    private Integer expectedCount;

    private BigDecimal completionRate;

    private String signal;
}
