package com.hq.backend.event;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.event.dto.EventCreateRequest;
import com.hq.backend.event.dto.EventResponse;
import com.hq.backend.event.dto.EventReviewRequest;
import com.hq.backend.event.dto.EventReviewResponse;
import com.hq.backend.event.dto.EventUpdateRequest;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public List<EventResponse> list(
            @CurrentUserId UUID userId, @RequestParam OffsetDateTime from, @RequestParam OffsetDateTime to) {
        return eventService.list(userId, from.toInstant(), to.toInstant());
    }

    @GetMapping("/next")
    public EventResponse next(@CurrentUserId UUID userId) {
        return eventService.next(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse create(@CurrentUserId UUID userId, @Valid @RequestBody EventCreateRequest request) {
        return eventService.create(userId, request);
    }

    @GetMapping("/{eventId}")
    public EventResponse get(@CurrentUserId UUID userId, @PathVariable UUID eventId) {
        return eventService.get(userId, eventId);
    }

    @PatchMapping("/{eventId}")
    public EventResponse update(
            @CurrentUserId UUID userId, @PathVariable UUID eventId, @RequestBody EventUpdateRequest request) {
        return eventService.update(userId, eventId, request);
    }

    @DeleteMapping("/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID eventId) {
        eventService.delete(userId, eventId);
    }

    @PostMapping("/{eventId}/review")
    public EventReviewResponse review(
            @CurrentUserId UUID userId, @PathVariable UUID eventId, @Valid @RequestBody EventReviewRequest request) {
        return eventService.answerReview(userId, eventId, request);
    }
}
