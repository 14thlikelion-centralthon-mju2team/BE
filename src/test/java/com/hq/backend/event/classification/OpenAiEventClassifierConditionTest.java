package com.hq.backend.event.classification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenAiEventClassifierConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(AiClassificationConfig.class, OpenAiClientConfig.class, NoOpEventClassifier.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void incomplete_configuration_keeps_the_no_op_classifier() {
        contextRunner.run(context -> assertThat(context.getBeansOfType(EventClassifier.class))
                .hasSize(1)
                .allSatisfy((name, classifier) -> assertThat(classifier).isInstanceOf(NoOpEventClassifier.class)));

        contextRunner.withPropertyValues("openai.classification.enabled=true")
                .run(context -> assertThat(context.getBeansOfType(EventClassifier.class)).hasSize(1));
        contextRunner.withPropertyValues("openai.api-key=test-key")
                .run(context -> assertThat(context.getBeansOfType(EventClassifier.class)).hasSize(1));
    }

    @Test
    void complete_exact_configuration_creates_primary_openai_classifier() {
        contextRunner.withPropertyValues(
                        "openai.classification.enabled=true",
                        "openai.api-key=test-key",
                        "openai.classification.privacy-policy-version=privacy-v1",
                        "openai.model=gpt-4o-mini-2024-07-18")
                .run(context -> {
                    assertThat(context.getBeansOfType(EventClassifier.class)).hasSize(2);
                    assertThat(context.getBean(EventClassifier.class)).isInstanceOf(OpenAiEventClassifier.class);
                    assertThat(context).hasSingleBean(OpenAiEventClassifier.class);
                });
    }

    @Test
    void model_mismatch_does_not_prevent_boot_and_keeps_no_op_classifier() {
        contextRunner.withPropertyValues(
                        "openai.classification.enabled=true",
                        "openai.api-key=test-key",
                        "openai.classification.privacy-policy-version=privacy-v1",
                        "openai.model=gpt-4o-mini")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBeansOfType(EventClassifier.class)).hasSize(1);
                    assertThat(context.getBean(EventClassifier.class)).isInstanceOf(NoOpEventClassifier.class);
                });
    }
}
