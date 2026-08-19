package com.hq.backend.provider;

import java.time.Instant;
import org.springframework.stereotype.Component;

// ponytail: 기상청·에어코리아 실 연동(M1) 전까지 쓰는 고정값 스텁 — 위치·시각 무관하게 무해한 값을 반환한다.
@Component
public class StubEnvironmentProvider implements EnvironmentProvider {

    @Override
    public EnvironmentSnapshot fetch(GeoPoint point, Instant at) {
        return new EnvironmentSnapshot(
                3.0, 40, 22.0, 10, at, "stub",
                20, "moderate", 17.0, 27.0, "stub-air");
    }
}
