package com.hq.backend.user;

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

// ERD v3 USER_CREDENTIAL — 이메일 계정만 행을 가진다(google 전용 계정은 없음). PK가
// users.user_id를 그대로 공유하는 약한 엔티티.
@Entity
@Table(name = "user_credential")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCredential {

    @Id
    private UUID userId;

    @Setter
    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String passwordAlgo;

    @Setter
    @Column(nullable = false)
    private Instant passwordUpdatedAt;

    @Setter
    @Column(nullable = false)
    private short failedAttempts;

    @Setter
    private Instant lockedUntil;
}
