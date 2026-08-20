package com.hq.backend.event.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@Conditional(OpenAiClassifierReadyCondition.class)
public class OpenAiClientConfig {

    @Bean
    @Qualifier("openAiRestClient")
    RestClient openAiRestClient(AiClassificationProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    @Primary
    OpenAiEventClassifier openAiEventClassifier(
            @Qualifier("openAiRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            AiClassificationProperties properties,
            AiClassificationMetrics metrics) {
        return new OpenAiEventClassifier(restClient, objectMapper, properties, metrics);
    }
}
