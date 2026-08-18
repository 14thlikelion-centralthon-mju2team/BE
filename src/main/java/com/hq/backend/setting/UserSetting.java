package com.hq.backend.setting;

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

// SET-03. 행 미존재 = 온보딩 미완료 → GET /me/settings는 DB 컬럼 기본값으로 응답한다
// (V6 마이그레이션 default와 동일 상수를 SettingsService에 둔다). PATCH가 최초 저장을 만든다.
@Entity
@Table(name = "user_setting")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSetting {

    @Id
    private UUID userId;

    @Setter
    private Integer initialPrepMinutes;

    @Setter
    @Column(nullable = false)
    private int arrivalBufferMinutes;

    @Setter
    @Column(nullable = false)
    private String notificationSensitivity;

    @Setter
    @Column(nullable = false)
    private boolean personalizationEnabled;

    @Setter
    @Column(nullable = false)
    private boolean autoManageEnabled;

    @Setter
    @Column(nullable = false)
    private boolean wellnessEventEnabled;

    @Setter
    @Column(nullable = false)
    private boolean lockscreenHideSensitive;

    @Setter
    @Column(nullable = false)
    private Instant updatedAt;
}
