package com.hq.backend.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StubRouteProviderTest {

    private final StubRouteProvider provider = new StubRouteProvider();

    @Test
    void search_returns_route_with_outdoor_sec_within_total() {
        Instant at = Instant.parse("2026-08-17T00:00:00Z");
        List<RouteOption> options = provider.search(new GeoPoint(37.5, 127.0), new GeoPoint(37.6, 127.1), "departAt", at);

        assertThat(options).hasSize(1);
        RouteOption option = options.get(0);
        assertThat(option.departAt()).isEqualTo(at);
        assertThat(option.etaAt()).isEqualTo(at.plusSeconds(option.totalSec()));
        assertThat(option.outdoorSec()).isLessThanOrEqualTo(option.totalSec());
    }
}
