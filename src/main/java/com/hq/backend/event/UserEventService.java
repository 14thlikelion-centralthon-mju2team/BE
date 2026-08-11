package com.hq.backend.event;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.dto.CreateEventRequest;
import com.hq.backend.event.dto.EventResponse;
import com.hq.backend.event.dto.UpdateEventRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserEventService {

    private final UserEventRepository userEventRepository;

    @Transactional(readOnly = true)
    public List<EventResponse> listEvents(UUID userId) {
        return userEventRepository.findByUserIdOrderByStartsAtAsc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public EventResponse createEvent(UUID userId, CreateEventRequest request) {
        validateRange(request.startsAt(), request.endsAt());

        UserEvent event = userEventRepository.save(UserEvent.builder()
                .userId(userId)
                .title(request.title())
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .placeText(request.placeText())
                .createdAt(Instant.now())
                .build());

        return toResponse(event);
    }

    @Transactional
    public EventResponse updateEvent(UUID userId, UUID eventId, UpdateEventRequest request) {
        UserEvent event = findOwnedEvent(userId, eventId);

        if (request.title() != null) {
            event.setTitle(request.title());
        }
        if (request.startsAt() != null) {
            event.setStartsAt(request.startsAt());
        }
        if (request.endsAt() != null) {
            event.setEndsAt(request.endsAt());
        }
        if (request.placeText() != null) {
            event.setPlaceText(request.placeText());
        }
        validateRange(event.getStartsAt(), event.getEndsAt());

        return toResponse(event);
    }

    @Transactional
    public void deleteEvent(UUID userId, UUID eventId) {
        // archived_at이 없는 테이블이라 소프트 삭제가 불가능 — 하드 삭제.
        userEventRepository.delete(findOwnedEvent(userId, eventId));
    }

    private UserEvent findOwnedEvent(UUID userId, UUID eventId) {
        return userEventRepository.findByIdAndUserId(eventId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "일정을 찾을 수 없습니다."));
    }

    private void validateRange(Instant startsAt, Instant endsAt) {
        if (endsAt.isBefore(startsAt)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVENT_RANGE", "endsAt은 startsAt보다 빠를 수 없습니다.");
        }
    }

    private EventResponse toResponse(UserEvent event) {
        return new EventResponse(
                event.getId(), event.getTitle(), event.getStartsAt(), event.getEndsAt(), event.getPlaceText(), event.getCreatedAt());
    }
}
