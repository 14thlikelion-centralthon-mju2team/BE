package com.hq.backend.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.place.dto.CreatePlaceRequest;
import com.hq.backend.place.dto.PlaceResponse;
import com.hq.backend.place.dto.UpdatePlaceRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlaceServiceTest {

    @Mock private PlaceRepository placeRepository;
    @Mock private PlaceVisitRepository placeVisitRepository;

    private PlaceService service() {
        return new PlaceService(placeRepository, placeVisitRepository);
    }

    @Test
    void radiusM을_안_주면_기본값_300이_들어간다() {
        var request = new CreatePlaceRequest("집", 37.5, 127.0, null, null);
        ArgumentCaptor<Place> captor = ArgumentCaptor.forClass(Place.class);
        when(placeRepository.save(captor.capture())).thenAnswer(inv -> captor.getValue());

        PlaceResponse response = service().createPlace(UUID.randomUUID(), request);

        assertThat(response.radiusM()).isEqualTo(300);
    }

    @Test
    void radiusM이_100_미만이면_거부한다() {
        var request = new CreatePlaceRequest("집", 37.5, 127.0, 99, null);

        assertThatThrownBy(() -> service().createPlace(UUID.randomUUID(), request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_RADIUS");
    }

    @Test
    void radiusM이_2000_초과면_거부한다() {
        var request = new CreatePlaceRequest("집", 37.5, 127.0, 2001, null);

        assertThatThrownBy(() -> service().createPlace(UUID.randomUUID(), request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_RADIUS");
    }

    @Test
    void 다른_유저의_장소를_수정하려_하면_404() {
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        when(placeRepository.findByIdAndUserIdAndArchivedAtIsNull(placeId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updatePlace(userId, placeId, new UpdatePlaceRequest(null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "PLACE_NOT_FOUND");
    }

    @Test
    void 삭제된_장소에_enter하면_404() {
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        when(placeRepository.findByIdAndUserIdAndArchivedAtIsNull(placeId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().enter(userId, placeId))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "PLACE_NOT_FOUND");
    }

    @Test
    void enter_이력이_없는_장소를_exit하면_거부한다() {
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        when(placeRepository.findByIdAndUserIdAndArchivedAtIsNull(placeId, userId))
                .thenReturn(Optional.of(Place.builder().id(placeId).userId(userId).createdAt(Instant.now()).build()));
        when(placeVisitRepository.findFirstByUserIdAndPlaceIdAndExitedAtIsNullOrderByEnteredAtDesc(userId, placeId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().exit(userId, placeId))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "OPEN_VISIT_NOT_FOUND");
    }

    @Test
    void enter를_연속_호출하면_이전_visit을_닫고_새로_연다() {
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        PlaceVisit openVisit = PlaceVisit.builder()
                .id(1L)
                .userId(userId)
                .placeId(placeId)
                .enteredAt(Instant.now().minusSeconds(60))
                .build();
        when(placeRepository.findByIdAndUserIdAndArchivedAtIsNull(placeId, userId))
                .thenReturn(Optional.of(Place.builder().id(placeId).userId(userId).createdAt(Instant.now()).build()));
        when(placeVisitRepository.findFirstByUserIdAndPlaceIdAndExitedAtIsNullOrderByEnteredAtDesc(userId, placeId))
                .thenReturn(Optional.of(openVisit));
        when(placeVisitRepository.save(org.mockito.ArgumentMatchers.any(PlaceVisit.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service().enter(userId, placeId);

        assertThat(openVisit.getExitedAt()).isNotNull();
    }
}
