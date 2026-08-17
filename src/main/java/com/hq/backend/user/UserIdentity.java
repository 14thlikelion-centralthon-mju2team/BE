package com.hq.backend.user;

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

// ERD v3 USER_IDENTITY — 로그인 제공자별 식별자. 한 계정이 email+google 두 identity를
// 가질 수 있다(TRD §10.1 계정 연결 규칙). uq_identity_provider(provider, provider_uid)가
// DB에서 중복을 막는다.
@Entity
@Table(name = "user_identity")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID identityId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String provider; // email | google | apple

    @Column(nullable = false)
    private String providerUid;

    @Column(nullable = false)
    private Instant linkedAt;

    @Setter
    private Instant revokedAt;
}
