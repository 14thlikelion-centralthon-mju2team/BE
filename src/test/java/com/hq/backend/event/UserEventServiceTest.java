package com.hq.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.dto.CreateEventRequest;
import com.hq.backend.event.dto.EventResponse;
import com.hq.backend.event.dto.UpdateEventRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserEventServiceTest {

    @Mock private UserEventRepository userEventRepository;

    private UserEventService service() {
        return new UserEventService(userEventRepository);
    }

    @Test
    void endsAt이_startsAt보다_빠르면_생성을_거부한다() {
        Instant now = Instant.now();
        var request = new CreateEventRequest("면접", now, now.minusSeconds(60), null);

        assertThatThrownBy(() -> service().createEvent(UUID.randomUUID(), request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_EVENT_RANGE");
    }

    @Test
    void 정상_범위면_생성된다() {
        Instant startsAt = Instant.now();
        Instant endsAt = startsAt.plusSeconds(3600);
        var request = new CreateEventRequest("면접", startsAt, endsAt, "강남역");
        ArgumentCaptor<UserEvent> captor = ArgumentCaptor.forClass(UserEvent.class);
        when(userEventRepository.save(captor.capture())).thenAnswer(inv -> captor.getValue());

        EventResponse response = service().createEvent(UUID.randomUUID(), request);

        assertThat(response.title()).isEqualTo("면접");
        assertThat(response.placeText()).isEqualTo("강남역");
    }

    @Test
    void 다른_유저의_일정을_수정하려_하면_404() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(userEventRepository.findByIdAndUserId(eventId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service()
                        .updateEvent(userId, eventId, new UpdateEventRequest(null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "EVENT_NOT_FOUND");
    }

    @Test
    void 수정으로_endsAt이_startsAt보다_빨라지면_거부한다() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant startsAt = Instant.now();
        Instant endsAt = startsAt.plusSeconds(3600);
        UserEvent existing = UserEvent.builder()
                .id(eventId).userId(userId).startsAt(startsAt).endsAt(endsAt).createdAt(Instant.now()).build();
        when(userEventRepository.findByIdAndUserId(eventId, userId)).thenReturn(Optional.of(existing));

        var request = new UpdateEventRequest(null, endsAt.plusSeconds(1), null, null); // startsAt을 endsAt 뒤로

        assertThatThrownBy(() -> service().updateEvent(userId, eventId, request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_EVENT_RANGE");
    }
}
