package com.hq.backend.place;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.place.dto.CreatePlaceRequest;
import com.hq.backend.place.dto.PlaceResponse;
import com.hq.backend.place.dto.PlaceVisitResponse;
import com.hq.backend.place.dto.UpdatePlaceRequest;
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

// /places/{id}/enter, /exit는 좌표를 받지 않는다 — 거리 계산은 OS가 하고 서버엔 place id +
// 시각만 전달된다 (FR-011 AC-002).
@RestController
@RequestMapping("/places")
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceService placeService;

    @GetMapping
    public List<PlaceResponse> list(@CurrentUserId UUID userId) {
        return placeService.listPlaces(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceResponse create(@CurrentUserId UUID userId, @Valid @RequestBody CreatePlaceRequest request) {
        return placeService.createPlace(userId, request);
    }

    @PatchMapping("/{id}")
    public PlaceResponse update(
            @CurrentUserId UUID userId, @PathVariable UUID id, @RequestBody UpdatePlaceRequest request) {
        return placeService.updatePlace(userId, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        placeService.deletePlace(userId, id);
    }

    @PostMapping("/{id}/enter")
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceVisitResponse enter(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return placeService.enter(userId, id);
    }

    @PostMapping("/{id}/exit")
    public PlaceVisitResponse exit(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return placeService.exit(userId, id);
    }
}
