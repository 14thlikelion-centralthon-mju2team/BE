package com.hq.backend.provider;

import java.time.Instant;

/**
 * Provider environment snapshot. Raw values and provider provenance are preserved;
 * unavailable air data stays null rather than being inferred by the Backend.
 *
 * <p>sky/pm10Grade/pm25Grade는 공개 환경 API(GET /environment/current)만 쓰는 값이라
 * 없으면 null이다. 어휘는 나머지 필드와 같은 영문 lower_snake이고, 사람이 읽는 한국어
 * 표기는 응답 DTO에서만 만든다.</p>
 */
public record EnvironmentSnapshot(
        double uvIndex,
        int pm10,
        double tempC,
        int precipitationProb,
        Instant asOf,
        String provider,
        Integer pm25,
        String airGrade,
        Double feelsLikeMinCelsius,
        Double feelsLikeMaxCelsius,
        String airProvider,
        String sky,
        String pm10Grade,
        String pm25Grade
) {
    public EnvironmentSnapshot(double uvIndex, int pm10, double tempC, int precipitationProb,
            Instant asOf, String provider) {
        this(uvIndex, pm10, tempC, precipitationProb, asOf, provider, null, null, null, null, null,
                null, null, null);
    }

    public EnvironmentSnapshot(double uvIndex, int pm10, double tempC, int precipitationProb,
            Instant asOf, String provider, Integer pm25, String airGrade,
            Double feelsLikeMinCelsius, Double feelsLikeMaxCelsius, String airProvider) {
        this(uvIndex, pm10, tempC, precipitationProb, asOf, provider, pm25, airGrade,
                feelsLikeMinCelsius, feelsLikeMaxCelsius, airProvider, null, null, null);
    }
}
