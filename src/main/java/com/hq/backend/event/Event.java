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
import lombok.Setter;

// ERD v3.1 `event`. 외부 캘린더 제목 원문은 저장하지 않는다(절대 원칙 8) — title 컬럼 자체가 없다.
// 표시명은 사용자가 입력·승인한 displayLabel만 (TRD D17). UserEvent(구 Vium user_events)를
// 대체하는 새 엔티티이며, UserEvent는 calendar 패키지의 기존 테스트가 아직 참조하고 있어
// 함께 남겨두고 있다 — calendar 재설계가 끝나면 정리 대상.
@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID eventId;

    @Column(nullable = false)
    private UUID userId;

    private UUID calendarSourceId;

    private String externalEventId;

    @Column(nullable = false)
    private String sourceType; // internal | external | map_search

    @Setter
    @Column(nullable = false)
    private Instant startsAt;

    @Setter
    private Instant endsAt;

    @Setter
    @Column(nullable = false)
    private boolean isAllDay;

    @Setter
    @Column(nullable = false)
    private String locationState; // required_resolved | required_missing | not_required | undecided

    @Setter
    private String destinationName;

    @Setter
    private Double destinationLat;

    @Setter
    private Double destinationLng;

    @Setter
    private String meetingUrl;

    @Setter
    private String eventKind;

    @Setter
    private String displayLabel; // 사용자가 입력·승인한 값만. 외부 제목 원문은 절대 여기 들어가지 않는다

    @Setter
    @Column(nullable = false)
    private boolean autoManageExcluded;

    @Setter
    @Column(nullable = false)
    private String status; // planned -> notified -> preparing -> enroute -> arrived -> closed (+ skipped/cancelled/unresolved)

    @Column(nullable = false)
    private Instant createdAt;
}
