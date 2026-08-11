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

@Entity
@Table(name = "places")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Setter
    @Column(nullable = false)
    private String label;

    @Setter
    @Column(nullable = false)
    private double lat;

    @Setter
    @Column(nullable = false)
    private double lng;

    // DB check 제약(100~2000)과 기본값(300)은 V1__init.sql이 정의 — 서비스 레이어에서 그대로 넘긴다.
    // name 명시 필요: Spring의 기본 네이밍 전략은 뒤에 소문자가 안 따라오는 trailing 대문자 앞엔
    // 언더스코어를 안 넣어서, radiusM이 무명시 시 radius_m이 아니라 radiusm으로 매핑된다.
    @Setter
    @Column(name = "radius_m", nullable = false)
    private int radiusM;

    @Setter
    private String kakaoPlaceId;

    @Setter
    private Instant archivedAt;

    @Column(nullable = false)
    private Instant createdAt;
}
