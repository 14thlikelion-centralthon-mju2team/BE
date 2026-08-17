package com.hq.backend.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// TR-03. clientEventId UNIQUE가 오프라인 재전송 멱등성을 흡수한다 — 중복은 오류가 아니라
// 정상 응답(duplicated)이다. 지오펜스 판정(actionSource='geo')도 이 테이블로 들어오되
// 좌표는 저장하지 않는다(절대 원칙 8).
@Entity
@Table(name = "event_action_log")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID actionLogId;

    @Column(nullable = false)
    private UUID eventId;

    private UUID planId;

    private UUID notificationId;

    @Column(nullable = false)
    private String actionType; // prep_started | snoozed | departed | item_checked | excluded

    @Column(nullable = false)
    private String actionSource; // user | geo | system

    @Column(nullable = false)
    private Instant actionAt; // 기기 시각(TR-02)

    @Column(nullable = false)
    private Instant receivedAt;

    private BigDecimal confidence;

    @Column(nullable = false)
    private boolean clockSkew; // |actionAt - receivedAt| > 120초면 true, 학습 제외 대상

    @Column(nullable = false)
    private UUID clientEventId;
}
