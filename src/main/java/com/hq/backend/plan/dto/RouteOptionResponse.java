package com.hq.backend.plan.dto;

import com.hq.backend.plan.RouteOption;
import java.time.Instant;
import java.util.UUID;

public record RouteOptionResponse(
        UUID routeOptionId,
        int routeRank,
        String routeType,
        int totalMinutes,
        int walkMinutes,
        int transferCount,
        Instant departAt,
        Instant arriveAt
) {

    public static RouteOptionResponse from(RouteOption route) {
        return new RouteOptionResponse(
                route.getRouteOptionId(),
                route.getRouteRank(),
                route.getRouteType(),
                route.getTotalMinutes(),
                route.getWalkMinutes(),
                route.getTransferCount(),
                route.getDepartAt(),
                route.getArriveAt());
    }
}
