package com.hq.backend.event.classification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class OpenAiClassifierReadyCondition implements Condition {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClassifierReadyCondition.class);

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String apiKey = context.getEnvironment().getProperty("openai.api-key", "");
        String policyVersion = context.getEnvironment().getProperty("openai.classification.privacy-policy-version", "");
        String model = context.getEnvironment().getProperty("openai.model", AiClassificationGate.PINNED_MODEL);
        boolean enabled = context.getEnvironment().getProperty("openai.classification.enabled", Boolean.class, false);

        boolean ready = enabled
                && hasText(apiKey)
                && hasText(policyVersion)
                && AiClassificationGate.PINNED_MODEL.equals(model);
        if (!ready && (enabled || hasText(apiKey) || hasText(policyVersion) || !AiClassificationGate.PINNED_MODEL.equals(model))) {
            log.warn("OpenAI event classifier disabled: failureReason=configuration_not_ready");
        }
        return ready;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
