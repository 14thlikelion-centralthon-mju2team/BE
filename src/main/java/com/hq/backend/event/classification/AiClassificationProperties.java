package com.hq.backend.event.classification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "openai")
@Validated
public record AiClassificationProperties(
        URI baseUrl,
        String apiKey,
        String model,
        @Min(1) int connectTimeoutMs,
        @Min(1) int readTimeoutMs,
        @Valid Classification classification) {

    public AiClassificationProperties {
        baseUrl = baseUrl == null ? URI.create("https://api.openai.com/v1") : baseUrl;
        apiKey = apiKey == null ? "" : apiKey;
        model = model == null ? "gpt-4o-mini-2024-07-18" : model;
        classification = classification == null ? Classification.defaults() : classification;
    }

    public record Classification(
            boolean enabled,
            @Min(0) @Max(100) int rolloutPercent,
            @Min(0) int maxPerSync,
            @Min(1) int maxConcurrency,
            String privacyPolicyVersion,
            String classifierVersion,
            String promptVersion,
            String schemaVersion) {

        private static Classification defaults() {
            return new Classification(
                    false, 0, 5, 2, "", "event-online-review-v1", "event-online-ko-v1", "event-online-v1");
        }
    }
}
