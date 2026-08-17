package com.hq.backend.personalization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// PK가 event.event_id를 공유하는 약한 엔티티 — 일정당 사후 평가 1건, 재제출은 갱신.
// 판단 데이터가 충분(지오펜스 confidence>=0.6)하면 클라이언트가 이 입력 UI 자체를 안 띄운다(PRD §12.10).
@Entity
@Table(name = "event_feedback")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventFeedback {

    @Id
    private UUID eventId;

    @Setter
    @Column(nullable = false)
    private String prepTimingAssessment; // too_early | appropriate | too_late | unknown

    @Setter
    private String arrivalResult; // early | on_time | rushed | late | unknown

    @Setter
    private String rushAssessment; // rushed | not_rushed | unknown — 북극성 지표 입력

    @Column(nullable = false)
    private Instant createdAt;
}
