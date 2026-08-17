package com.hq.backend.personalization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// TR-05 — 하나의 관측은 하나의 손잡이만 조정한다. 복수 원인을 기록할 수 있지만(복합 PK),
// 보정 라우팅은 confidence가 가장 높은 원인 하나만 사용한다.
@Entity
@Table(name = "event_delay_reason")
@IdClass(EventDelayReasonId.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventDelayReason {

    @Id
    private UUID eventId;

    @Id
    @Column(nullable = false)
    private String reasonCode; // prep_late | prep_overrun | depart_late | traffic | external

    @Column(nullable = false)
    private String reasonSource; // user | inferred

    private BigDecimal confidence;

    @Column(nullable = false)
    private Instant createdAt;
}
