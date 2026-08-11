package com.hq.backend.place;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.place.dto.CreatePlaceRequest;
import com.hq.backend.place.dto.PlaceResponse;
import com.hq.backend.place.dto.PlaceVisitResponse;
import com.hq.backend.place.dto.UpdatePlaceRequest;
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

    // V1__init.sql의 radius_m check 제약(100~2000)·기본값(300)과 동일 — Hibernate가 매 INSERT에
    // 값을 명시적으로 채우기 때문에 DB 기본값에 기대지 않고 여기서 직접 채운다.
    private static final int DEFAULT_RADIUS_M = 300;
    private static final int MIN_RADIUS_M = 100;
    private static final int MAX_RADIUS_M = 2000;

    private final PlaceRepository placeRepository;
    private final PlaceVisitRepository placeVisitRepository;

    @Transactional(readOnly = true)
    public List<PlaceResponse> listPlaces(UUID userId) {
        return placeRepository.findByUserIdAndArchivedAtIsNull(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public PlaceResponse createPlace(UUID userId, CreatePlaceRequest request) {
        int radiusM = resolveRadius(request.radiusM());

        Place place = placeRepository.save(Place.builder()
                .userId(userId)
                .label(request.label())
                .lat(request.lat())
                .lng(request.lng())
                .radiusM(radiusM)
                .kakaoPlaceId(request.kakaoPlaceId())
                .createdAt(Instant.now())
                .build());

        return toResponse(place);
    }

    @Transactional
    public PlaceResponse updatePlace(UUID userId, UUID placeId, UpdatePlaceRequest request) {
        Place place = findOwnedPlace(userId, placeId);

        if (request.label() != null) {
            place.setLabel(request.label());
        }
        if (request.lat() != null) {
            place.setLat(request.lat());
        }
        if (request.lng() != null) {
            place.setLng(request.lng());
        }
        if (request.radiusM() != null) {
            place.setRadiusM(resolveRadius(request.radiusM()));
        }

        return toResponse(place);
    }

    @Transactional
    public void deletePlace(UUID userId, UUID placeId) {
        Place place = findOwnedPlace(userId, placeId);
        place.setArchivedAt(Instant.now());
    }

    @Transactional
    public PlaceVisitResponse enter(UUID userId, UUID placeId) {
        findOwnedPlace(userId, placeId);

        // 지오펜스 enter 이벤트는 중복 발생할 수 있다(앱 재시작 등) — 이전에 안 닫힌 방문이
        // 남아있으면 고아로 만들지 말고 지금 시각으로 먼저 닫은 뒤 새로 연다.
        placeVisitRepository
                .findFirstByUserIdAndPlaceIdAndExitedAtIsNullOrderByEnteredAtDesc(userId, placeId)
                .ifPresent(open -> open.setExitedAt(Instant.now()));

        PlaceVisit visit = placeVisitRepository.save(PlaceVisit.builder()
                .userId(userId)
                .placeId(placeId)
                .enteredAt(Instant.now())
                .build());

        return toResponse(visit);
    }

    @Transactional
    public PlaceVisitResponse exit(UUID userId, UUID placeId) {
        findOwnedPlace(userId, placeId);

        PlaceVisit visit = placeVisitRepository
                .findFirstByUserIdAndPlaceIdAndExitedAtIsNullOrderByEnteredAtDesc(userId, placeId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "OPEN_VISIT_NOT_FOUND", "진행 중인 방문 기록이 없습니다."));
        visit.setExitedAt(Instant.now());

        return toResponse(visit);
    }

    private Place findOwnedPlace(UUID userId, UUID placeId) {
        return placeRepository.findByIdAndUserIdAndArchivedAtIsNull(placeId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다."));
    }

    private int resolveRadius(Integer radiusM) {
        if (radiusM == null) {
            return DEFAULT_RADIUS_M;
        }
        if (radiusM < MIN_RADIUS_M || radiusM > MAX_RADIUS_M) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RADIUS",
                    "radiusM은 %d~%d 사이여야 합니다.".formatted(MIN_RADIUS_M, MAX_RADIUS_M));
        }
        return radiusM;
    }

    private PlaceResponse toResponse(Place place) {
        return new PlaceResponse(
                place.getId(),
                place.getLabel(),
                place.getLat(),
                place.getLng(),
                place.getRadiusM(),
                place.getKakaoPlaceId(),
                place.getCreatedAt());
    }

    private PlaceVisitResponse toResponse(PlaceVisit visit) {
        return new PlaceVisitResponse(visit.getId(), visit.getPlaceId(), visit.getEnteredAt(), visit.getExitedAt());
    }
}
