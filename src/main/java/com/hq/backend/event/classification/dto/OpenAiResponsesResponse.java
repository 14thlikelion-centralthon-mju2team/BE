package com.hq.backend.event.classification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OpenAiResponsesResponse(
        String status,
        ErrorDetails error,
        @JsonProperty("incomplete_details") IncompleteDetails incompleteDetails,
        String model,
        List<Output> output,
        Usage usage) {

    public record ErrorDetails(String code) {
    }

    public record IncompleteDetails(String reason) {
    }

    public record Output(String type, String role, List<Content> content) {
    }

    public record Content(String type, String text, String refusal) {
    }

    public record Usage(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens,
            @JsonProperty("total_tokens") Integer totalTokens) {
    }
}
