package com.hq.backend.event.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiClassificationMetricsTest {

    @Test
    void records_only_the_contract_metric_names_and_low_cardinality_tags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiClassificationMetrics metrics = new AiClassificationMetrics(registry);

        metrics.recordCall(AiCallOutcome.SUCCESS);
        metrics.recordLatency(Duration.ofMillis(12));
        metrics.addTokens(TokenDirection.INPUT, 17);
        metrics.addTokens(TokenDirection.OUTPUT, 5);
        metrics.recordReview(AiReviewOutcome.CREATED);
        metrics.addPurged(3);
        metrics.addDeleted(2);
        metrics.recordRetentionLag(Duration.ofSeconds(7));

        assertThat(registry.getMeters()).extracting(meter -> meter.getId().getName()).containsExactlyInAnyOrder(
                "ai_classification_calls_total", "ai_classification_latency_seconds",
                "ai_classification_tokens_total", "ai_classification_tokens_total",
                "ai_classification_reviews_total", "ai_classification_retention_purge_total",
                "ai_classification_retention_delete_total", "ai_classification_retention_lag_seconds");
        assertThat(registry.get("ai_classification_calls_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai_classification_calls_total").tag("outcome", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai_classification_tokens_total").tag("direction", "input").counter().count()).isEqualTo(17);
        assertThat(registry.get("ai_classification_reviews_total").tag("outcome", "created").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai_classification_latency_seconds").timer().count()).isEqualTo(1);
        assertThat(registry.get("ai_classification_retention_lag_seconds").timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isIn("outcome", "direction")));
        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getValue())
                .allMatch(value -> Set.of("success", "input", "output", "created").contains(value));
    }

    @Test
    void records_a_successful_provider_call_latency_and_usage_once_without_sensitive_tags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiClassificationMetrics metrics = new AiClassificationMetrics(registry);
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openai.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andRespond(withSuccess("""
                        {"status":"completed","model":"gpt-4o-mini-2024-07-18","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"{\\"questionType\\":\\"is_online\\",\\"suggestedValue\\":\\"online\\",\\"confidence\\":0.94}"}]}],"usage":{"input_tokens":12,"output_tokens":7,"total_tokens":19}}
                        """, MediaType.APPLICATION_JSON));

        new OpenAiEventClassifier(builder.build(), new ObjectMapper(), properties(), metrics)
                .classify(new EventClassificationInput("private calendar title"));

        assertThat(registry.get("ai_classification_calls_total").tag("outcome", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai_classification_latency_seconds").timer().count()).isEqualTo(1);
        assertThat(registry.get("ai_classification_tokens_total").tag("direction", "input").counter().count()).isEqualTo(12);
        assertThat(registry.get("ai_classification_tokens_total").tag("direction", "output").counter().count()).isEqualTo(7);
        assertThat(registry.find("ai_classification_tokens_total").tag("direction", "total").counter()).isNull();
        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getValue())
                .doesNotContain("private calendar title", "gpt-4o-mini-2024-07-18");
        server.verify();
    }

    @Test
    void records_consent_rollout_budget_and_busy_skips_without_provider_or_identifier_tags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiClassificationMetrics metrics = new AiClassificationMetrics(registry);
        AiClassificationProperties enabled = properties();
        AiClassificationGate consentGate = new AiClassificationGate(Mockito.mock(com.hq.backend.consent.UserConsentRepository.class), enabled, metrics);
        AiClassificationGate rolloutGate = new AiClassificationGate(Mockito.mock(com.hq.backend.consent.UserConsentRepository.class),
                new AiClassificationProperties(URI.create("https://openai.test/v1"), "test-api-key", "gpt-4o-mini-2024-07-18", 3000, 10000,
                        new AiClassificationProperties.Classification(true, 0, 5, 2, "privacy-v1", "classifier-v1", "prompt-v1", "schema-v1")), metrics);

        assertThat(consentGate.evaluate(java.util.UUID.randomUUID())).isEqualTo(AiGateOutcome.SKIPPED_CONSENT);
        assertThat(rolloutGate.evaluate(java.util.UUID.randomUUID())).isEqualTo(AiGateOutcome.SKIPPED_ROLLOUT);

        assertThat(registry.get("ai_classification_calls_total").tag("outcome", "skipped_consent").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai_classification_calls_total").tag("outcome", "skipped_rollout").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsOnly("outcome");
    }

    @Test
    void records_each_http_terminal_outcome_once_without_status_or_body_tags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiClassificationMetrics metrics = new AiClassificationMetrics(registry);
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openai.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://openai.test/v1/responses")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo("https://openai.test/v1/responses")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        OpenAiEventClassifier classifier = new OpenAiEventClassifier(builder.build(), new ObjectMapper(), properties(), metrics);

        classifier.classify(new EventClassificationInput("private title one"));
        classifier.classify(new EventClassificationInput("private title two"));

        assertThat(registry.get("ai_classification_calls_total").tag("outcome", "http_4xx").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai_classification_calls_total").tag("outcome", "http_5xx").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getValue())
                .doesNotContain("429", "503", "private title one", "private title two");
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("httpTimeoutFailures")
    void records_jdk_http_timeout_failures_as_timeout_once(java.io.IOException timeout) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiClassificationMetrics metrics = new AiClassificationMetrics(registry);
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openai.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://openai.test/v1/responses")).andRespond(request -> { throw timeout; });

        new OpenAiEventClassifier(builder.build(), new ObjectMapper(), properties(), metrics)
                .classify(new EventClassificationInput("private title"));

        assertThat(registry.get("ai_classification_calls_total").tag("outcome", "timeout").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai_classification_latency_seconds").timer().count()).isEqualTo(1);
        server.verify();
    }

    @Test
    void records_incomplete_before_validating_model_or_output_shape() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiClassificationMetrics metrics = new AiClassificationMetrics(registry);
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openai.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andRespond(withSuccess("{\"status\":\"in_progress\",\"output\":[]}", MediaType.APPLICATION_JSON));

        new OpenAiEventClassifier(builder.build(), new ObjectMapper(), properties(), metrics)
                .classify(new EventClassificationInput("private title"));

        assertThat(registry.get("ai_classification_calls_total").tag("outcome", "incomplete").counter().count()).isEqualTo(1);
        assertThat(registry.find("ai_classification_calls_total").tag("outcome", "invalid_schema").counter()).isNull();
        server.verify();
    }

    private static Stream<java.io.IOException> httpTimeoutFailures() {
        return Stream.of(new HttpTimeoutException("timeout detail"), new HttpConnectTimeoutException("connect timeout detail"));
    }

    @Test
    void records_budget_and_busy_skips_once_at_orchestration_boundaries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiClassificationMetrics metrics = new AiClassificationMetrics(registry);
        AiClassificationGate gate = Mockito.mock(AiClassificationGate.class);
        CalendarTitleNormalizer normalizer = Mockito.mock(CalendarTitleNormalizer.class);
        AiClassificationConcurrencyGuard guard = Mockito.mock(AiClassificationConcurrencyGuard.class);
        EventClassifier classifier = Mockito.mock(EventClassifier.class);
        EventClassificationReviewWriter writer = Mockito.mock(EventClassificationReviewWriter.class);
        EventClassificationOrchestrator orchestrator = new EventClassificationOrchestrator(
                gate, normalizer, guard, classifier, writer, metrics);
        java.util.UUID userId = java.util.UUID.randomUUID();

        assertThat(orchestrator.classifyCreated(userId, java.util.UUID.randomUUID(), "private title", 0))
                .isEqualTo(ClassificationAttemptOutcome.SKIPPED_BUDGET);
        Mockito.when(gate.evaluate(userId)).thenReturn(AiGateOutcome.ALLOWED);
        Mockito.when(normalizer.normalize("private title")).thenReturn(java.util.Optional.of("private title"));
        Mockito.when(guard.tryAcquire()).thenReturn(false);
        assertThat(orchestrator.classifyCreated(userId, java.util.UUID.randomUUID(), "private title", 1))
                .isEqualTo(ClassificationAttemptOutcome.SKIPPED_BUSY);

        assertThat(registry.get("ai_classification_calls_total").tag("outcome", "skipped_budget").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai_classification_calls_total").tag("outcome", "skipped_busy").counter().count()).isEqualTo(1);
        Mockito.verifyNoInteractions(classifier, writer);
    }

    private AiClassificationProperties properties() {
        return new AiClassificationProperties(
                URI.create("https://openai.test/v1"), "test-api-key", "gpt-4o-mini-2024-07-18", 3000, 10000,
                new AiClassificationProperties.Classification(
                        true, 100, 5, 2, "privacy-v1", "classifier-v1", "prompt-v1", "schema-v1"));
    }
}
