package com.hq.backend.event.classification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AiClassificationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(AiClassificationConfig.class, NoOpEventClassifier.class);

    @Test
    void default_yaml_creates_one_properties_and_one_no_op_classifier() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AiClassificationProperties.class);
            assertThat(context).hasSingleBean(EventClassifier.class);
            assertThat(context.getBean(EventClassifier.class)).isInstanceOf(NoOpEventClassifier.class);
            assertThat(context).doesNotHaveBean("openAiEventClassifier");
        });
    }

    @Test
    void invalid_nested_classification_values_prevent_context_startup() {
        contextRunner.withPropertyValues("openai.classification.rollout-percent=101")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
        contextRunner.withPropertyValues("openai.classification.max-per-sync=-1")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
        contextRunner.withPropertyValues("openai.classification.max-concurrency=0")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @Test
    void no_op_classifier_always_returns_empty() {
        assertThat(new NoOpEventClassifier().classify(new EventClassificationInput("private calendar title")))
                .isEmpty();
    }
}
