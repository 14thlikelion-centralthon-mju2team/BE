package com.hq.backend.personalization;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.personalization.dto.EventExecutionResponse;
import com.hq.backend.personalization.dto.EventFeedbackRequest;
import com.hq.backend.personalization.dto.EventFeedbackResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events/{eventId}")
@RequiredArgsConstructor
public class EventOutcomeController {

    private final EventOutcomeService eventOutcomeService;

    @GetMapping("/execution")
    public EventExecutionResponse getExecution(@CurrentUserId UUID userId, @PathVariable UUID eventId) {
        return eventOutcomeService.getExecution(userId, eventId);
    }

    @PostMapping("/feedback")
    public EventFeedbackResponse submitFeedback(
            @CurrentUserId UUID userId, @PathVariable UUID eventId, @Valid @RequestBody EventFeedbackRequest request) {
        return eventOutcomeService.submitFeedback(userId, eventId, request);
    }
}
