package com.hq.backend.calendar;

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

// user_id·provider가 유니크라(테이블당 1인당 1행) 재연결은 새 행이 아니라 기존 행을
// 업데이트한다 — CalendarService 참고.
@Entity
@Table(name = "calendar_connections")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CalendarConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String provider;

    // BytesEncryptor로 암호화된 refresh_token — 평문은 절대 저장하지 않는다.
    @Setter
    @Column(nullable = false)
    private byte[] refreshTokenEnc;

    @Setter
    @Column(nullable = false)
    private String scope;

    @Setter
    @Column(nullable = false)
    private Instant connectedAt;

    @Setter
    private Instant revokedAt;
}
