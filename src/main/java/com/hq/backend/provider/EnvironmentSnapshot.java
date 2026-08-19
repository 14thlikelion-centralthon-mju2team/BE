package com.hq.backend.provider;

import java.time.Instant;

/**
 * Provider environment snapshot. Raw values and provider provenance are preserved;
 * unavailable air data stays null rather than being inferred by the Backend.
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
        String airProvider
) {
    public EnvironmentSnapshot(double uvIndex, int pm10, double tempC, int precipitationProb,
            Instant asOf, String provider) {
        this(uvIndex, pm10, tempC, precipitationProb, asOf, provider, null, null, null, null, null);
    }
}
