package com.hq.backend.place.dto;

import com.hq.backend.place.PlaceType;

// 전부 nullable — 온 필드만 반영한다.
public record PlaceUpdateRequest(
        PlaceType placeType,
        String placeName,
        String address,
        Double lat,
        Double lng,
        Boolean isPrimary
) {
}
