package com.hq.backend.plan;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// PK가 plan_revision.plan_id를 그대로 공유하는 약한 엔티티(TRD §4.1 Ch.6). 생성 시
// planId를 PlanRevision과 동일한 값으로 명시적으로 채운다 — 별도 시퀀스/UUID 생성 없음.
@Entity
@Table(name = "plan_context")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanContext {

    @Id
    private UUID planId;

    private BigDecimal temperature;
    private BigDecimal feelsLike;
    private BigDecimal precipitationProb;
    private Short uvIndex;
    private Integer pm10;
    private Integer pm25;
    private Integer trafficDelayMinutes;
    private Integer estimatedOutdoorMinutes;
    private String weatherProvider;
    private String airProvider;
    private String trafficProvider;
    private Instant observedAt;
}
