package com.hq.backend.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class StubEnvironmentProviderTest {

    private final StubEnvironmentProvider provider = new StubEnvironmentProvider();

    @Test
    void fetch_echoes_requested_time_as_asOf() {
        Instant at = Instant.parse("2026-08-17T00:00:00Z");
        EnvironmentSnapshot snapshot = provider.fetch(new GeoPoint(37.5, 127.0), at);

        assertThat(snapshot.asOf()).isEqualTo(at);
        assertThat(snapshot.provider()).isEqualTo("stub");
        assertThat(snapshot.pm25()).isEqualTo(20);
        assertThat(snapshot.airGrade()).isEqualTo("moderate");
        assertThat(snapshot.feelsLikeMinCelsius()).isEqualTo(17.0);
        assertThat(snapshot.feelsLikeMaxCelsius()).isEqualTo(27.0);
        assertThat(snapshot.airProvider()).isEqualTo("stub-air");
    }
}
