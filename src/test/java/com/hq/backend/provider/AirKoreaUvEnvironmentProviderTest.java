package com.hq.backend.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AirKoreaUvEnvironmentProviderTest {

    private static final String AIR_FIXTURE = """
            {"response":{"body":{"items":[{
              "dataTime":"2026-08-20 02:00", "pm10Value":"45", "pm25Value":"22",
              "pm10Grade":"2", "pm25Grade":"3"
            }]}}}
            """;
    private static final String UV_FIXTURE = """
            {"response":{"body":{"items":{"item":[{"h0":"8"}]}}}}
            """;

    @Test
    void fetch_preserves_airkorea_raw_values_and_provider_grade_and_kma_uv() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET)).andExpect(request -> {
                    assertThat(request.getURI().getPath()).contains("getMsrstnAcctoRltmMesureDnsty");
                    assertThat(request.getURI().getQuery()).contains("stationName=종로구");
                })
                .andRespond(withSuccess(AIR_FIXTURE, MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.GET)).andExpect(request -> {
                    assertThat(request.getURI().getPath()).contains("getUVIdxV4");
                    assertThat(request.getURI().getQuery()).contains("areaNo=1100000000");
                })
                .andRespond(withSuccess(UV_FIXTURE, MediaType.APPLICATION_JSON));

        AirKoreaUvEnvironmentProvider provider = provider(builder.build());
        EnvironmentSnapshot snapshot = provider.fetch(new GeoPoint(37.5665, 126.9780), Instant.parse("2026-08-19T17:35:00Z"));

        assertThat(snapshot.uvIndex()).isEqualTo(8.0);
        assertThat(snapshot.pm10()).isEqualTo(45);
        assertThat(snapshot.pm25()).isEqualTo(22);
        // PM2.5's grade 3 comes directly from AirKorea; PM numerical values are not reclassified.
        assertThat(snapshot.airGrade()).isEqualTo("bad");
        assertThat(snapshot.airProvider()).isEqualTo("airkorea");
        assertThat(snapshot.asOf()).isEqualTo(Instant.parse("2026-08-19T17:00:00Z"));
        server.verify();
    }

    @Test
    void fetch_degrades_air_and_uv_independently_when_external_calls_fail() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET)).andRespond(withServerError());
        server.expect(method(HttpMethod.GET)).andRespond(withServerError());

        AirKoreaUvEnvironmentProvider provider = provider(builder.build());
        Instant at = Instant.parse("2026-08-19T17:35:00Z");
        EnvironmentSnapshot snapshot = provider.fetch(new GeoPoint(37.5665, 126.9780), at);

        assertThat(snapshot.uvIndex()).isEqualTo(-1.0);
        assertThat(snapshot.pm10()).isEqualTo(-1);
        assertThat(snapshot.pm25()).isNull();
        assertThat(snapshot.airGrade()).isNull();
        assertThat(snapshot.airProvider()).isNull();
        assertThat(snapshot.asOf()).isEqualTo(at);
        server.verify();
    }

    private AirKoreaUvEnvironmentProvider provider(RestClient restClient) throws Exception {
        EnvironmentProvider weather = (point, at) -> new EnvironmentSnapshot(
                -1.0, -1, 21.0, 30, at, "kma", null, null, 18.0, 27.0, null);
        AirKoreaUvEnvironmentProvider provider = new AirKoreaUvEnvironmentProvider(weather, restClient);
        set(provider, "airKoreaServiceKey", "air-key");
        set(provider, "stationName", "종로구");
        set(provider, "measurementUrl", "https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty");
        set(provider, "uvAreaNo", "1100000000");
        set(provider, "uvIndexUrl", "https://apis.data.go.kr/1360000/LivingWthrIdxServiceV4/getUVIdxV4");
        set(provider, "kmaServiceKey", "kma-key");
        return provider;
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
