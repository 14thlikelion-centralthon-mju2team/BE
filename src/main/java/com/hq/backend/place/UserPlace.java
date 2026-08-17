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
// (API 명세 §5). lat/lng는 TRD §14.3이 애플리케이션 레벨 AES-GCM 암호화를 요구하지만,
// 이 엔티티는 평문 컬럼 매핑까지만 — 암호화 컨버터는 Service 계층 붙일 때 추가한다.
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
    private double lat;

    @Setter
    @Column(nullable = false)
    private double lng;

    @Setter
    @Column(nullable = false)
    private boolean isPrimary;

    @Setter
    private Instant deletedAt;
}
