package com.hq.backend.event.classification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiClassificationProperties.class)
public class AiClassificationConfig {
}
