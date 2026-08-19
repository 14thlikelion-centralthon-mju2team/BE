package com.hq.backend.provider;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

// 실제 기상청 key가 없을 때만 사용하는 고정값 fixture다. 운영 key가 있으면 live provider가 선택된다.
@Component
@ConditionalOnExpression("'${provider.kma.service-key:}'.isBlank()")
public class StubEnvironmentProvider implements EnvironmentProvider {

    @Override
    public EnvironmentSnapshot fetch(GeoPoint point, Instant at) {
        return new EnvironmentSnapshot(
                3.0, 40, 22.0, 10, at, "stub",
                20, "moderate", 17.0, 27.0, "stub-air");
    }
}
