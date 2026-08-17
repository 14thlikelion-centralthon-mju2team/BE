package com.hq.backend.wellness;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// remindIntervalMinutes·dailyEventCap은 사용자가 직접 설정한 값이다 — 서비스가
// 재도포 주기를 판단하지 않는다(절대 원칙 3, PRD §14.7). 항목별 하루 상한이며
// WELLNESS_EVENT_SCHEDULE.sequence_no(일정당 상한)와는 별개다(TR-11).
@Entity
@Table(name = "user_wellness_pref")
@IdClass(UserWellnessPrefId.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserWellnessPref {

    @Id
    private UUID userId;

    @Id
    @Column(nullable = false)
    private String wellnessTopic; // uv | pm | temp | rain | hydration

    @Setter
    @Column(nullable = false)
    private boolean isEnabled;

    @Setter
    private Integer remindIntervalMinutes;

    @Setter
    @Column(nullable = false)
    private int dailyEventCap;

    @Setter
    @Column(nullable = false)
    private Instant updatedAt;
}
