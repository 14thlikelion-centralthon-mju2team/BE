package com.hq.backend.place;

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

// SET-01. plan_revision.origin_place_id / user_prep_rule.apply_place_id가 참조하는 테이블.
// DELETE는 소프트 삭제(deleted_at) — 과거 계획의 originSnapshot*은 스냅샷이라 영향받지 않는다
// (API 명세 §5). lat/lng는 TRD §14.3 요구대로 애플리케이션 레벨 AES-GCM 암호화된 바이트로
// 저장한다(PlaceService가 BytesEncryptor로 (역)직렬화 — CalendarConnection.refreshTokenEnc와
// 같은 패턴). 숫자 범위 검증은 암호화된 값에 걸 수 없어 DB CHECK 대신 PlaceService에서 한다.
@Entity
@Table(name = "user_place")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID placeId;

    @Column(nullable = false)
    private UUID userId;

    @Setter
    @Column(nullable = false)
    private String placeType; // home | school | work | other

    @Setter
    @Column(nullable = false)
    private String placeName;

    @Setter
    @Column(nullable = false)
    private String address;

    @Setter
    @Column(nullable = false)
    private byte[] latEnc;

    @Setter
    @Column(nullable = false)
    private byte[] lngEnc;

    @Setter
    @Column(nullable = false)
    private boolean isPrimary;

    @Setter
    private Instant deletedAt;
}
