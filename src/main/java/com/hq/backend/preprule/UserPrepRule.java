package com.hq.backend.preprule;

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

// TRD §4.4 준비 항목 3단 체인의 1단계(원형). ruleCategory(구분) × actionType(동작) 2축 구조 —
// v2.0의 평면 kind enum은 폐기됐다(MIGRATION §3). apply_* 조건은 MVP에서 전부 null(무조건 적용)만
// 쓴다(P1: 조건부 자동 적용). defaultMinutes는 actionType='timed_routine'일 때만 값을 가지며
// ck_prep_minutes CHECK가 DB에서 이 규칙을 강제한다 — 서비스 계층에서 재검증만 하고 재구현하지 않는다.
@Entity
@Table(name = "user_prep_rule")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPrepRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID prepRuleId;

    @Column(nullable = false)
    private UUID userId;

    @Setter
    @Column(nullable = false)
    private String ruleName;

    @Column(nullable = false)
    private String ruleCategory; // supplement | medication | personal_item | routine | general_item

    @Column(nullable = false)
    private String actionType; // carry | consume | purchase | timed_routine

    @Column(nullable = false)
    private String ruleTiming; // pre_departure | post_arrival

    @Setter
    private Integer defaultMinutes; // actionType='timed_routine'일 때만 not null

    private String applyEventKind;
    private String applyTimeBand;
    private UUID applyPlaceId;
    private String applyWeather;

    @Setter
    @Column(nullable = false)
    private boolean isRequired;

    @Setter
    @Column(nullable = false)
    private boolean isSensitive; // ruleCategory='medication'이면 서버가 강제로 true

    @Column(nullable = false)
    private boolean fromChip;

    @Setter
    @Column(nullable = false)
    private boolean isActive;

    @Column(nullable = false)
    private Instant createdAt;

    @Setter
    private Instant deletedAt;
}
