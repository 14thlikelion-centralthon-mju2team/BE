package com.hq.backend.route;

import java.time.Instant;
import java.util.UUID;

public record RouteSearchResponse(
        UUID routeOptionId,
        int routeRank,
        String routeType,
        int totalMinutes,
        int walkMinutes,
        int transferCount,
        Instant departAt,
        Instant arriveAt
) {

    static RouteSearchResponse from(RouteSearchOption option) {
        return new RouteSearchResponse(
                option.getRouteSearchOptionId(),
                option.getRouteRank(),
                option.getRouteType(),
                option.getTotalSeconds() / 60,
                option.getWalkSeconds() / 60,
                option.getTransferCount(),
                option.getDepartAt(),
                option.getArriveAt());
    }
}
