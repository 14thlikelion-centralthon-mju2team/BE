package com.hq.backend.route;

import com.hq.backend.common.auth.CurrentUserId;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/routes")
@RequiredArgsConstructor
public class RouteSearchController {

    private final RouteSearchService routeSearchService;

    @GetMapping("/search")
    public List<RouteSearchResponse> search(
            @CurrentUserId UUID userId,
            @RequestParam(required = false) UUID originPlaceId,
            @RequestParam(required = false) Double originLat,
            @RequestParam(required = false) Double originLng,
            @RequestParam(required = false) Double destLat,
            @RequestParam(required = false) Double destLng,
            @RequestParam(required = false) String destName,
            @RequestParam(required = false) String anchorMode,
            @RequestParam(required = false) OffsetDateTime at) {
        return routeSearchService.search(userId, new RouteSearchRequest(
                originPlaceId, originLat, originLng, destLat, destLng, destName, anchorMode,
                at == null ? null : at.toInstant()));
    }
}
