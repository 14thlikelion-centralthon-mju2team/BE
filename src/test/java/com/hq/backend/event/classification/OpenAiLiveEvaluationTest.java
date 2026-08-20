package com.hq.backend.event.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * An intentionally operational test. It only sends synthetic, valid golden-set titles after all policy,
 * dedicated-key, pinned-model, request-budget, and conservative cost-budget gates pass locally.
 */
@Tag("openai-eval")
class OpenAiLiveEvaluationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int REQUIRED_REQUESTS = 160;
    private static final int MAX_INPUT_TOKENS_PER_REQUEST = 2_048;
    private static final int MAX_OUTPUT_TOKENS_PER_REQUEST = 80;

    @Test
    void live_evaluation_fails_closed_before_network_when_any_required_approval_is_missing() {
        assertThatThrownBy(() -> LiveEvaluationPolicy.from(Map.of(
                "OPENAI_EVAL_POLICY_APPROVED", "true",
                "OPENAI_EVAL_DEDICATED_KEY_APPROVED", "true",
                "OPENAI_EVAL_PRICING_APPROVED", "true",
                "OPENAI_API_KEY", "sk-eval-only",
                "OPENAI_MODEL", AiClassificationGate.PINNED_MODEL,
                "OPENAI_EVAL_MAX_REQUESTS", "160",
                "OPENAI_EVAL_MAX_COST_USD", "1.00",
                "OPENAI_EVAL_INPUT_USD_PER_1M", "0.15")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_EVAL_OUTPUT_USD_PER_1M");
    }

    @Test
    void enabled_live_evaluation_calls_the_real_classifier_and_enforces_quality_latency_tokens_requests_and_cost() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("OPENAI_EVAL_ENABLED")),
                "set OPENAI_EVAL_ENABLED=true to opt in to live evaluation");

        List<GoldenCase> cases = loadGoldenCases();
        List<GoldenCase> validCases = cases.stream().filter(GoldenCase::validInput).toList();
        assertThat(validCases).hasSize(REQUIRED_REQUESTS);
        LiveEvaluationPolicy policy = LiveEvaluationPolicy.from(System.getenv());
        policy.requireBudgetFor(validCases.size()); // All gates run before the first provider request.

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OpenAiEventClassifier classifier = new OpenAiEventClassifier(
                RestClient.builder().baseUrl("https://api.openai.com/v1").build(),
                OBJECT_MAPPER,
                new AiClassificationProperties(
                        URI.create("https://api.openai.com/v1"), policy.apiKey(), AiClassificationGate.PINNED_MODEL,
                        3_000, 5_000, new AiClassificationProperties.Classification(true, 100, REQUIRED_REQUESTS, 1,
                        "live-eval-only", "event-online-review-v1", "event-online-ko-v1", "event-online-v1")),
                new AiClassificationMetrics(registry));

        List<Long> latencyMillis = new ArrayList<>();
        List<Long> outputTokens = new ArrayList<>();
        int onlineTruePositive = 0;
        int onlineFalsePositive = 0;
        int onlineFalseNegative = 0;
        int offlineTruePositive = 0;
        int offlineFalsePositive = 0;
        int offlineFalseNegative = 0;
        BigDecimal observedCost = BigDecimal.ZERO;

        for (int requestIndex = 0; requestIndex < validCases.size(); requestIndex++) {
            policy.requireRequestAllowed(requestIndex, observedCost);
            GoldenCase goldenCase = validCases.get(requestIndex);
            long beforeOutput = counterCount(registry, "ai_classification_tokens_total", "direction", "output");
            long beforeInput = counterCount(registry, "ai_classification_tokens_total", "direction", "input");
            long startedAt = System.nanoTime();
            var result = classifier.classify(new EventClassificationInput(goldenCase.title()));
            latencyMillis.add(Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
            long outputDelta = counterCount(registry, "ai_classification_tokens_total", "direction", "output") - beforeOutput;
            long inputDelta = counterCount(registry, "ai_classification_tokens_total", "direction", "input") - beforeInput;
            outputTokens.add(outputDelta);
            observedCost = observedCost.add(policy.costFor(inputDelta, outputDelta));

            assertThat(result).as("synthetic golden case %s must have a strict classifier response", goldenCase.id()).isPresent();
            String actual = result.orElseThrow().suggestedValue();
            if ("online".equals(goldenCase.expected())) {
                if ("online".equals(actual)) onlineTruePositive++;
                else onlineFalseNegative++;
                if ("offline".equals(actual)) offlineFalsePositive++;
            } else {
                if ("offline".equals(actual)) offlineTruePositive++;
                else offlineFalseNegative++;
                if ("online".equals(actual)) onlineFalsePositive++;
            }
            policy.requireCostWithinCap(observedCost);
        }

        double onlineF1 = f1(onlineTruePositive, onlineFalsePositive, onlineFalseNegative);
        double offlineF1 = f1(offlineTruePositive, offlineFalsePositive, offlineFalseNegative);
        assertThat((onlineF1 + offlineF1) / 2.0).as("macro F1 for synthetic golden set").isGreaterThanOrEqualTo(0.90);
        assertThat(percentile95(latencyMillis)).as("provider latency p95 in milliseconds").isLessThanOrEqualTo(5_000L);
        assertThat(percentile95(outputTokens)).as("provider output-token p95").isLessThanOrEqualTo(50L);
        assertThat(observedCost).as("approved live-evaluation cost cap").isLessThanOrEqualTo(policy.costCap());
    }

    private List<GoldenCase> loadGoldenCases() throws Exception {
        InputStream resource = getClass().getResourceAsStream("/ai/event-online-golden-v1.jsonl");
        assertThat(resource).as("synthetic golden-set resource").isNotNull();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            return reader.lines().map(this::parseGoldenCase).toList();
        }
    }

    private GoldenCase parseGoldenCase(String line) {
        try {
            JsonNode row = OBJECT_MAPPER.readTree(line);
            return new GoldenCase(row.path("id").asText(), row.path("title").asText(),
                    row.path("expected").asText(), row.path("validInput").asBoolean());
        } catch (Exception exception) {
            throw new AssertionError("golden set must be valid JSONL", exception);
        }
    }

    private long counterCount(SimpleMeterRegistry registry, String name, String tag, String value) {
        Counter counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? 0L : Math.round(counter.count());
    }

    private long percentile95(List<Long> values) {
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }

    private double f1(int truePositive, int falsePositive, int falseNegative) {
        int denominator = 2 * truePositive + falsePositive + falseNegative;
        return denominator == 0 ? 0.0 : (2.0 * truePositive) / denominator;
    }

    private record GoldenCase(String id, String title, String expected, boolean validInput) {
    }

    static final class LiveEvaluationPolicy {
        private final String apiKey;
        private final int maxRequests;
        private final BigDecimal costCap;
        private final BigDecimal inputUsdPerMillion;
        private final BigDecimal outputUsdPerMillion;

        private LiveEvaluationPolicy(String apiKey, int maxRequests, BigDecimal costCap,
                                     BigDecimal inputUsdPerMillion, BigDecimal outputUsdPerMillion) {
            this.apiKey = apiKey;
            this.maxRequests = maxRequests;
            this.costCap = costCap;
            this.inputUsdPerMillion = inputUsdPerMillion;
            this.outputUsdPerMillion = outputUsdPerMillion;
        }

        static LiveEvaluationPolicy from(Map<String, String> environment) {
            requireExact(environment, "OPENAI_EVAL_POLICY_APPROVED", "true");
            requireExact(environment, "OPENAI_EVAL_DEDICATED_KEY_APPROVED", "true");
            requireExact(environment, "OPENAI_EVAL_PRICING_APPROVED", "true");
            requireExact(environment, "OPENAI_MODEL", AiClassificationGate.PINNED_MODEL);
            String key = requireText(environment, "OPENAI_API_KEY");
            if (!key.startsWith("sk-")) throw new IllegalStateException("OPENAI_API_KEY must be a dedicated OpenAI key");
            int maxRequests = parsePositiveInt(environment, "OPENAI_EVAL_MAX_REQUESTS");
            BigDecimal cap = parsePositiveDecimal(environment, "OPENAI_EVAL_MAX_COST_USD");
            BigDecimal inputRate = parsePositiveDecimal(environment, "OPENAI_EVAL_INPUT_USD_PER_1M");
            BigDecimal outputRate = parsePositiveDecimal(environment, "OPENAI_EVAL_OUTPUT_USD_PER_1M");
            return new LiveEvaluationPolicy(key, maxRequests, cap, inputRate, outputRate);
        }

        void requireBudgetFor(int requiredRequests) {
            if (requiredRequests != REQUIRED_REQUESTS || maxRequests != REQUIRED_REQUESTS) {
                throw new IllegalStateException("OPENAI_EVAL_MAX_REQUESTS must exactly authorize the 160 valid synthetic cases");
            }
            BigDecimal worstCase = costFor(MAX_INPUT_TOKENS_PER_REQUEST, MAX_OUTPUT_TOKENS_PER_REQUEST)
                    .multiply(BigDecimal.valueOf(requiredRequests));
            if (costCap.compareTo(worstCase) < 0) {
                throw new IllegalStateException("OPENAI_EVAL_MAX_COST_USD is below the conservative pre-network request budget");
            }
        }

        void requireRequestAllowed(int completedRequests, BigDecimal observedCost) {
            if (completedRequests >= maxRequests) throw new IllegalStateException("approved live-evaluation request cap reached");
            requireCostWithinCap(observedCost);
        }

        void requireCostWithinCap(BigDecimal observedCost) {
            if (observedCost.compareTo(costCap) > 0) throw new IllegalStateException("approved live-evaluation cost cap exceeded");
        }

        BigDecimal costFor(long inputTokens, long outputTokens) {
            return inputUsdPerMillion.multiply(BigDecimal.valueOf(Math.max(0L, inputTokens)))
                    .add(outputUsdPerMillion.multiply(BigDecimal.valueOf(Math.max(0L, outputTokens))))
                    .divide(BigDecimal.valueOf(1_000_000L), 12, RoundingMode.HALF_UP);
        }

        String apiKey() { return apiKey; }
        BigDecimal costCap() { return costCap; }

        private static void requireExact(Map<String, String> environment, String key, String expected) {
            if (!expected.equals(environment.get(key))) throw new IllegalStateException(key + " must equal " + expected);
        }

        private static String requireText(Map<String, String> environment, String key) {
            String value = environment.get(key);
            if (value == null || value.isBlank()) throw new IllegalStateException(key + " must be set");
            return value;
        }

        private static int parsePositiveInt(Map<String, String> environment, String key) {
            try {
                int value = Integer.parseInt(requireText(environment, key));
                if (value <= 0) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException exception) {
                throw new IllegalStateException(key + " must be a positive integer");
            }
        }

        private static BigDecimal parsePositiveDecimal(Map<String, String> environment, String key) {
            try {
                BigDecimal value = new BigDecimal(requireText(environment, key));
                if (value.signum() <= 0) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException exception) {
                throw new IllegalStateException(key + " must be a positive decimal");
            }
        }
    }
}
