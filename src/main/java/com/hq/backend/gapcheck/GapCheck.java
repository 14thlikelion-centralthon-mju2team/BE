package com.hq.backend.gapcheck;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// V4__gap_checks.sql: unique(user_id, log_date) — 같은 날 중복 응답은 서비스에서 사전 검증한다.
@Entity
@Table(name = "gap_checks")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GapCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private LocalDate logDate;

    @Column(nullable = false)
    private String response;

    @Column(nullable = false)
    private Instant createdAt;
}
