package com.hq.backend.event.classification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Deliberately opt-in operational evaluation gate. The live evaluator is not a CI test because it sends the
 * synthetic golden-set titles to the provider and needs an approved policy plus a dedicated evaluation key.
 */
@Tag("openai-eval")
class OpenAiLiveEvaluationTest {

    @Test
    void enabled_live_evaluation_requires_explicit_policy_and_dedicated_key_before_any_network_call() {
        Assumptions.assumeTrue("true".equals(System.getenv("OPENAI_EVAL_ENABLED")),
                "set OPENAI_EVAL_ENABLED=true to opt in to live evaluation");

        assertThat(System.getenv("OPENAI_EVAL_POLICY_APPROVED"))
                .as("live provider processing approval")
                .isEqualTo("true");
        assertThat(System.getenv("OPENAI_API_KEY"))
                .as("dedicated live evaluation API key")
                .isNotBlank();
        assertThat(System.getenv("OPENAI_EVAL_MAX_COST_USD"))
                .as("approved live evaluation cost cap")
                .isNotBlank();

        // The approved operator records provider outputs in the release evidence and verifies:
        // macro-F1 >= 0.90, latency p95 <= 5 seconds, and output tokens p95 <= 50.
        // This repository intentionally does not make network calls absent an approved execution policy.
    }
}
