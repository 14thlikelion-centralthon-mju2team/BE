package com.hq.backend.event.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventClassificationGoldenSetTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void synthetic_korean_golden_set_enforces_schema_balance_privacy_and_strict_provider_contract() throws Exception {
        List<GoldenCase> cases = loadGoldenCases();

        assertThat(cases).hasSize(200);
        assertThat(cases).extracting(GoldenCase::id).doesNotHaveDuplicates();
        assertThat(cases).filteredOn(GoldenCase::validInput).hasSize(160);
        assertThat(cases).filteredOn(caseItem -> !caseItem.validInput()).hasSize(40);
        assertThat(cases).filteredOn(caseItem -> caseItem.validInput() && "online".equals(caseItem.expected())).hasSize(80);
        assertThat(cases).filteredOn(caseItem -> caseItem.validInput() && "offline".equals(caseItem.expected())).hasSize(80);

        CalendarTitleNormalizer normalizer = new CalendarTitleNormalizer();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openai.fixture/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiEventClassifier classifier = classifier(builder.build());
        int providerCalls = 0;
        int schemaValidResponses = 0;
        int serverAcceptedResponses = 0;

        for (GoldenCase goldenCase : cases) {
            var normalized = normalizer.normalize(goldenCase.title());
            assertThat(normalized.isPresent()).isEqualTo(goldenCase.validInput());
            if (!goldenCase.validInput()) {
                continue;
            }
            providerCalls++;
            server.expect(ExpectedCount.once(), requestTo("https://openai.fixture/v1/responses"))
                    .andExpect(request -> assertTitleIsTheOnlyProviderInput(request, goldenCase.title()))
                    .andRespond(withSuccess(completedResponse(goldenCase.expected()), MediaType.APPLICATION_JSON));
        }

        for (GoldenCase goldenCase : cases) {
            if (!goldenCase.validInput()) {
                continue;
            }
            var normalized = normalizer.normalize(goldenCase.title());
            var result = classifier.classify(new EventClassificationInput(normalized.orElseThrow()));
            assertThat(result).isPresent();
            schemaValidResponses++;
            assertThat(result.orElseThrow().suggestedValue()).isEqualTo(goldenCase.expected());
            serverAcceptedResponses++;
        }

        assertThat(schemaValidResponses / (double) providerCalls).isGreaterThanOrEqualTo(0.99);
        assertThat(serverAcceptedResponses).isEqualTo(providerCalls);
        server.verify();
    }

    @Test
    void invalid_golden_inputs_are_rejected_at_the_orchestrator_boundary_without_provider_calls() throws Exception {
        EventClassifier provider = mock(EventClassifier.class);
        AiClassificationGate gate = mock(AiClassificationGate.class);
        when(gate.evaluate(org.mockito.ArgumentMatchers.any())).thenReturn(AiGateOutcome.ALLOWED);
        EventClassificationOrchestrator orchestrator = new EventClassificationOrchestrator(gate,
                new CalendarTitleNormalizer(), new AiClassificationConcurrencyGuard(testProperties()), provider,
                mock(EventClassificationReviewWriter.class), new AiClassificationMetrics(new SimpleMeterRegistry()));

        for (GoldenCase goldenCase : loadGoldenCases()) {
            if (!goldenCase.validInput()) {
                assertThat(orchestrator.classifyCreated(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                        0L, goldenCase.title(), 1)).isEqualTo(ClassificationAttemptOutcome.SKIPPED_INVALID_INPUT);
            }
        }
        verifyNoInteractions(provider);
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
            assertThat(row.isObject()).isTrue();
            assertThat(row.fieldNames()).toIterable().containsExactlyInAnyOrder(
                    "id", "title", "expected", "validInput", "category");
            assertThat(row.path("id").isTextual()).isTrue();
            assertThat(row.path("title").isTextual() || row.path("title").isNull()).isTrue();
            assertThat(row.path("expected").asText()).isIn("online", "offline");
            assertThat(row.path("validInput").isBoolean()).isTrue();
            assertThat(row.path("category").isTextual()).isTrue();
            String title = row.path("title").isNull() ? null : row.path("title").asText();
            return new GoldenCase(row.path("id").asText(), title,
                    row.path("expected").asText(), row.path("validInput").asBoolean(), row.path("category").asText());
        } catch (Exception exception) {
            throw new AssertionError("golden set must be strict JSONL", exception);
        }
    }

    private void assertTitleIsTheOnlyProviderInput(org.springframework.http.client.ClientHttpRequest request, String title) {
        try {
            JsonNode body = OBJECT_MAPPER.readTree(request.getBody().toString());
            JsonNode titlePayload = OBJECT_MAPPER.readTree(body.at("/input/0/content/0/text").asText());
            assertThat(titlePayload.fieldNames()).toIterable().containsExactly("calendarTitle");
            assertThat(titlePayload.path("calendarTitle").asText()).isEqualTo(title);
            assertThat(body.at("/input/0/content/0/type").asText()).isEqualTo("input_text");
            assertThat(body.path("store").asBoolean()).isFalse();
            assertThat(body.has("tools")).isFalse();
        } catch (Exception exception) {
            throw new AssertionError("provider request must contain only the golden title", exception);
        }
    }

    private OpenAiEventClassifier classifier(RestClient restClient) {
        return new OpenAiEventClassifier(restClient, OBJECT_MAPPER, testProperties(),
                new AiClassificationMetrics(new SimpleMeterRegistry()));
    }

    private AiClassificationProperties testProperties() {
        return new AiClassificationProperties(
                URI.create("https://openai.fixture/v1"), "fixture-api-key", AiClassificationGate.PINNED_MODEL,
                3_000, 10_000, new AiClassificationProperties.Classification(true, 100, 200, 2,
                "privacy-v1", "classifier-v1", "prompt-v1", "schema-v1"));
    }

    private String completedResponse(String expected) {
        String result = "{\"questionType\":\"is_online\",\"suggestedValue\":\"%s\",\"confidence\":0.95}".formatted(expected);
        return "{\"status\":\"completed\",\"model\":\"%s\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":%s}]}]}"
                .formatted(AiClassificationGate.PINNED_MODEL, OBJECT_MAPPER.valueToTree(result));
    }

    private record GoldenCase(String id, String title, String expected, boolean validInput, String category) {
    }
}
