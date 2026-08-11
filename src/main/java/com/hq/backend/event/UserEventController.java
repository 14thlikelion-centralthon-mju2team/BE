package com.hq.backend.event;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.event.dto.CreateEventRequest;
import com.hq.backend.event.dto.EventResponse;
import com.hq.backend.event.dto.UpdateEventRequest;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class UserEventController {

    private final UserEventService userEventService;

    @GetMapping
    public List<EventResponse> list(@CurrentUserId UUID userId) {
        return userEventService.listEvents(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse create(@CurrentUserId UUID userId, @Valid @RequestBody CreateEventRequest request) {
        return userEventService.createEvent(userId, request);
    }

    @PatchMapping("/{id}")
    public EventResponse update(
            @CurrentUserId UUID userId, @PathVariable UUID id, @RequestBody UpdateEventRequest request) {
        return userEventService.updateEvent(userId, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        userEventService.deleteEvent(userId, id);
    }
}
