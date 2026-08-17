package com.hq.backend.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

// TRD 11.2. 기상청 단기예보(TMP=기온, POP=강수확률)만 실제로 연동했다.
// PM10(에어코리아 — 좌표→측정소 매핑 필요)과 자외선지수(기상청 별도 API — 응답 필드 미검증)는
// 아직 없어 -1로 채워 둔다 (05-blocked-on-user.md 참고, 실 키로 검증 후 이어서 구현).
// 두 값이 완성될 때까지는 @Component가 아니라 StubEnvironmentProvider가 계속 쓰인다.
public class KmaEnvironmentProvider implements EnvironmentProvider {

    // 기상청 단기예보 격자 변환 상수 (LCC DFS 투영, 공식 문서 고정값)
    private static final double RE = 6371.00877;
    private static final double GRID = 5.0;
    private static final double SLAT1 = 30.0;
    private static final double SLAT2 = 60.0;
    private static final double OLON = 126.0;
    private static final double OLAT = 38.0;
    private static final double XO = 43;
    private static final double YO = 136;

    private static final int[] BASE_HOURS = {2, 5, 8, 11, 14, 17, 20, 23};

    private final RestClient restClient;

    @Value("${provider.kma.service-key}")
    private String serviceKey;

    @Value("${provider.kma.forecast-url}")
    private String forecastUrl;

    public KmaEnvironmentProvider(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public EnvironmentSnapshot fetch(GeoPoint point, Instant at) {
        int[] grid = toGrid(point.lat(), point.lng());
        String[] baseDateTime = baseDateTime(at);

        URI uri = UriComponentsBuilder.fromUriString(forecastUrl)
                .queryParam("serviceKey", serviceKey)
                .queryParam("dataType", "JSON")
                .queryParam("numOfRows", 100)
                .queryParam("pageNo", 1)
                .queryParam("base_date", baseDateTime[0])
                .queryParam("base_time", baseDateTime[1])
                .queryParam("nx", grid[0])
                .queryParam("ny", grid[1])
                .encode()
                .build()
                .toUri();

        KmaResponse response = restClient.get().uri(uri).retrieve().body(KmaResponse.class);
        List<KmaItem> items = response.response().body().items().item();

        double tempC = items.stream()
                .filter(item -> "TMP".equals(item.category()))
                .findFirst()
                .map(item -> Double.parseDouble(item.fcstValue()))
                .orElseThrow();
        int precipitationProb = items.stream()
                .filter(item -> "POP".equals(item.category()))
                .findFirst()
                .map(item -> Integer.parseInt(item.fcstValue()))
                .orElseThrow();

        return new EnvironmentSnapshot(-1.0, -1, tempC, precipitationProb, at, "kma");
    }

    private int[] toGrid(double lat, double lng) {
        double degRad = Math.PI / 180.0;
        double re = RE / GRID;
        double slat1 = SLAT1 * degRad;
        double slat2 = SLAT2 * degRad;
        double olon = OLON * degRad;
        double olat = OLAT * degRad;

        double sn = Math.tan(Math.PI * 0.25 + slat2 * 0.5) / Math.tan(Math.PI * 0.25 + slat1 * 0.5);
        sn = Math.log(Math.cos(slat1) / Math.cos(slat2)) / Math.log(sn);
        double sf = Math.tan(Math.PI * 0.25 + slat1 * 0.5);
        sf = Math.pow(sf, sn) * Math.cos(slat1) / sn;
        double ro = Math.tan(Math.PI * 0.25 + olat * 0.5);
        ro = re * sf / Math.pow(ro, sn);

        double ra = Math.tan(Math.PI * 0.25 + lat * degRad * 0.5);
        ra = re * sf / Math.pow(ra, sn);
        double theta = lng * degRad - olon;
        if (theta > Math.PI) theta -= 2.0 * Math.PI;
        if (theta < -Math.PI) theta += 2.0 * Math.PI;
        theta *= sn;

        int x = (int) Math.floor(ra * Math.sin(theta) + XO + 0.5);
        int y = (int) Math.floor(ro - ra * Math.cos(theta) + YO + 0.5);
        return new int[]{x, y};
    }

    // 단기예보는 02/05/08/11/14/17/20/23시에 발표되고, API 제공은 발표 후 약 10분 뒤부터라
    // 10분을 빼고 직전 발표 시각을 기준으로 삼는다.
    private String[] baseDateTime(Instant at) {
        ZonedDateTime zoned = at.atZone(ZoneId.of("Asia/Seoul")).minusMinutes(10);
        int hour = zoned.getHour();
        int baseHour = BASE_HOURS[0];
        for (int h : BASE_HOURS) {
            if (h <= hour) {
                baseHour = h;
            }
        }
        var date = zoned.toLocalDate();
        if (hour < BASE_HOURS[0]) {
            date = date.minusDays(1);
            baseHour = BASE_HOURS[BASE_HOURS.length - 1];
        }
        String baseDate = date.format(DateTimeFormatter.BASIC_ISO_DATE);
        String baseTime = String.format("%02d00", baseHour);
        return new String[]{baseDate, baseTime};
    }

    private record KmaResponse(KmaResponseBody response) {
    }

    private record KmaResponseBody(KmaBody body) {
    }

    private record KmaBody(KmaItems items) {
    }

    private record KmaItems(List<KmaItem> item) {
    }

    // jackson.property-naming-strategy: SNAKE_CASE(application.yaml 전역 설정)가 fcstValue를
    // fcst_value로 찾으려 하는 걸 막기 위해 실제 응답 필드명을 명시한다.
    private record KmaItem(String category, @JsonProperty("fcstValue") String fcstValue) {
    }
}
