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

// user_events에는 archived_at이 없다 — 삭제는 소프트가 아니라 하드 삭제(DELETE).
@Entity
@Table(name = "user_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    // title 저장 여부는 TRD Q-004 미결이지만 스키마는 이미 nullable로 확정돼 있다.
    @Setter
    private String title;

    @Setter
    @Column(nullable = false)
    private Instant startsAt;

    @Setter
    @Column(nullable = false)
    private Instant endsAt;

    @Setter
    private String placeText;

    @Column(nullable = false)
    private Instant createdAt;
}
