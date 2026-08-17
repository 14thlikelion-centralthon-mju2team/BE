package com.hq.backend.place;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.place.dto.PlaceRequest;
import com.hq.backend.place.dto.PlaceResponse;
import com.hq.backend.place.dto.PlaceUpdateRequest;
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
@RequestMapping("/places")
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceService placeService;

    @GetMapping
    public List<PlaceResponse> list(@CurrentUserId UUID userId) {
        return placeService.list(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceResponse create(@CurrentUserId UUID userId, @Valid @RequestBody PlaceRequest request) {
        return placeService.create(userId, request);
    }

    @PatchMapping("/{placeId}")
    public PlaceResponse update(
            @CurrentUserId UUID userId, @PathVariable UUID placeId, @RequestBody PlaceUpdateRequest request) {
        return placeService.update(userId, placeId, request);
    }

    @DeleteMapping("/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID placeId) {
        placeService.delete(userId, placeId);
    }
}
