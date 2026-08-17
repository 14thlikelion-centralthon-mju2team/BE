package com.hq.backend.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// TRD §4.4 준비 항목 3단 체인의 2단계 — USER_PREP_RULE의 apply_* 평가 결과로 일정마다 파생된다.
@Entity
@Table(name = "event_prep_item")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventPrepItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID eventPrepItemId;

    @Column(nullable = false)
    private UUID eventId;

    private UUID sourcePrepRuleId;

    @Column(nullable = false)
    private String itemName;

    @Column(nullable = false)
    private String actionType; // carry | consume | purchase | timed_routine

    @Column(nullable = false)
    private int estimatedMinutes;

    @Column(nullable = false)
    private boolean isRequired;

    @Column(nullable = false)
    private boolean isSensitive;

    @Column(nullable = false)
    private Instant createdAt;
}
