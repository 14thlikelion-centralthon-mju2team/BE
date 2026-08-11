package com.hq.backend.place.dto;

// PATCH 부분수정: null인 필드는 그대로 두고, 값이 있는 필드만 반영한다.
public record UpdatePlaceRequest(String label, Double lat, Double lng, Integer radiusM) {
}
