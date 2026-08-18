package com.hq.backend.place;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.place.dto.PlaceRequest;
import com.hq.backend.place.dto.PlaceResponse;
import com.hq.backend.place.dto.PlaceUpdateRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlaceService {

    private final UserPlaceRepository userPlaceRepository;
    private final PlaceCoordinateCodec codec;

    @Transactional(readOnly = true)
    public List<PlaceResponse> list(UUID userId) {
        return userPlaceRepository.findByUserIdAndDeletedAtIsNull(userId).stream()
                .map(place -> PlaceResponse.from(place, codec))
                .toList();
    }

    @Transactional
    public PlaceResponse create(UUID userId, PlaceRequest request) {
        validateCoordinates(request.lat(), request.lng());
        if (request.isPrimary()) {
            clearExistingPrimary(userId);
        }

        UserPlace saved = userPlaceRepository.save(UserPlace.builder()
                .userId(userId)
                .placeType(request.placeType().name().toLowerCase())
                .placeName(request.placeName())
                .address(request.address())
                .latEnc(codec.encode(request.lat()))
                .lngEnc(codec.encode(request.lng()))
                .isPrimary(request.isPrimary())
                .build());

        return PlaceResponse.from(saved, codec);
    }

    @Transactional
    public PlaceResponse update(UUID userId, UUID placeId, PlaceUpdateRequest request) {
        UserPlace place = findOwned(userId, placeId);

        if (request.placeType() != null) {
            place.setPlaceType(request.placeType().name().toLowerCase());
        }
        if (request.placeName() != null) {
            place.setPlaceName(request.placeName());
        }
        if (request.address() != null) {
            place.setAddress(request.address());
        }
        double lat = request.lat() != null ? request.lat() : codec.decode(place.getLatEnc());
        double lng = request.lng() != null ? request.lng() : codec.decode(place.getLngEnc());
        if (request.lat() != null || request.lng() != null) {
            validateCoordinates(lat, lng);
            place.setLatEnc(codec.encode(lat));
            place.setLngEnc(codec.encode(lng));
        }
        if (Boolean.TRUE.equals(request.isPrimary()) && !place.isPrimary()) {
            clearExistingPrimary(userId);
            place.setPrimary(true);
        } else if (Boolean.FALSE.equals(request.isPrimary())) {
            place.setPrimary(false);
        }

        return PlaceResponse.from(place, codec);
    }

    @Transactional
    public void delete(UUID userId, UUID placeId) {
        UserPlace place = findOwned(userId, placeId);
        place.setDeletedAt(Instant.now());
    }

    // uq_active_primary_place는 지연 검증이 아니라 문장 단위로 즉시 체크된다 — 기존 대표
    // 해제(UPDATE)를 새 대표 저장(INSERT/UPDATE)보다 먼저 실제로 내보내야 한다. Hibernate
    // 플러시 순서는 기본적으로 INSERT를 UPDATE보다 먼저 처리해서, 그냥 두면 새 행 INSERT
    // 시점에 기존 행이 아직 is_primary=true인 채로 남아 있어 제약을 위반한다.
    private void clearExistingPrimary(UUID userId) {
        userPlaceRepository.findByUserIdAndIsPrimaryTrueAndDeletedAtIsNull(userId)
                .ifPresent(existing -> {
                    existing.setPrimary(false);
                    userPlaceRepository.saveAndFlush(existing);
                });
    }

    private UserPlace findOwned(UUID userId, UUID placeId) {
        return userPlaceRepository.findByPlaceIdAndUserId(placeId, userId)
                .filter(place -> place.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다."));
    }

    // ck_user_place_lat/lng를 대신한다 — 암호화된 값은 DB가 범위를 검증할 수 없다.
    private void validateCoordinates(double lat, double lng) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "lat은 -90~90, lng는 -180~180 범위여야 합니다.");
        }
    }
}
