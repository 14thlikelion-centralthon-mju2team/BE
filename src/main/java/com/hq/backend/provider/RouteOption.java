package com.hq.backend.provider;

import java.time.Instant;
import java.util.List;

// TRD 11.1 — 제공자 중립 정규화. outdoorSec은 도보 leg 합으로, WIS의 O 입력이 된다.
// rawRef는 재조회 키만 담는다 — 원본 응답 전체는 저장하지 않는다(약관).
public record RouteOption(
        String id,
        String rank, // fastest | least_walk | least_transfer
        int totalSec,
        int walkSec,
        int transfers,
        int outdoorSec,
        List<Leg> legs,
        Instant departAt,
        Instant etaAt,
        String provider,
        String rawRef
) {
}
