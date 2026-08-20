package com.hq.backend.event.classification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record OpenAiResponsesRequest(
        String model,
        boolean store,
        @JsonProperty("tool_choice") String toolChoice,
        @JsonProperty("max_output_tokens") int maxOutputTokens,
        String instructions,
        List<InputMessage> input,
        Text text) {

    public record InputMessage(String role, List<InputContent> content) {
    }

    public record InputContent(String type, String text) {
    }

    public record Text(Format format) {
    }

    public record Format(String type, String name, boolean strict, Schema schema) {
    }

    public record Schema(
            String type,
            boolean additionalProperties,
            List<String> required,
            Map<String, Property> properties) {
    }

    public record Property(
            String type,
            @JsonProperty("enum") List<String> enumValues,
            java.math.BigDecimal minimum,
            java.math.BigDecimal maximum) {
    }
}
