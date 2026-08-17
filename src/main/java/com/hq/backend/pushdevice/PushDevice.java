package com.hq.backend.pushdevice;

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

// V6__ensom_v3_1_schema.sql push_device. installation_id에 UNIQUE 제약이 있어 같은 기기가
// 재등록(로그인 전환 포함)하면 새 행을 만들지 않고 이 엔티티를 갱신한다.
@Entity
@Table(name = "push_device")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID pushDeviceId;

    @Setter
    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID installationId;

    @Setter
    @Column(nullable = false)
    private String currentToken;

    @Setter
    @Column(nullable = false)
    private String tokenStatus; // active | inactive | invalid

    @Setter
    @Column(nullable = false)
    private String platform; // ios | android | web

    @Setter
    @Column(nullable = false)
    private Instant lastSeenAt;

    private Instant revokedAt;
}
