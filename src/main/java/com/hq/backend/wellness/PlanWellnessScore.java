package com.hq.backend.wellness;

import jakarta.persistence.Column;
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
import lombok.Setter;

// plan_revision과 PK를 공유하는 약한 엔티티. WIS는 알림 우선순위 값일 뿐 건강 점수가
// 아니다(score_purpose=priority_only, 절대 원칙 3). weightVersion이 바뀌어도 과거 점수는
// 소급 재계산하지 않고 버전별로 분리 집계한다(D15). 계산 자체는 wellness.core(이지호) 담당.
@Entity
@Table(name = "plan_wellness_score")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanWellnessScore {

    @Id
    private UUID planId;

    @Column(nullable = false)
    private BigDecimal uvLoad; // U 0~1

    @Column(nullable = false)
    private BigDecimal pmLoad; // P 0~1

    @Column(nullable = false)
    private BigDecimal thermalLoad; // T 0~1

    @Column(nullable = false)
    private BigDecimal outdoorLoad; // O 0~1, 상한 120분

    @Column(nullable = false)
    private BigDecimal interestMultiplier; // M 1.0~1.25

    @Column(nullable = false)
    private short wisScore; // 0~100

    @Column(nullable = false)
    private String wisBand; // low | mid | high

    @Column(nullable = false)
    private String weightVersion;

    // M3 일정 중 push 후보. eventArmed=false 또는 gate 차단이면 null이다.
    @Setter
    private String armedActionCode;

    @Column(nullable = false)
    private Instant calculatedAt;
}
