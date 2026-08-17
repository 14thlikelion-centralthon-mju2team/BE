package com.hq.backend.provider;

import java.time.Instant;

// TRD 11.2. WIS 웰니스 항목 중 수분(hydration)은 타이머 기반이라 여기 포함하지 않는다 — 나머지 4개(자외선,
// 미세먼지, 기온, 강수)만 외부 제공자 값이다.
public record EnvironmentSnapshot(
        double uvIndex,
        int pm10,
        double tempC,
        int precipitationProb,
        Instant asOf,
        String provider
) {
}
