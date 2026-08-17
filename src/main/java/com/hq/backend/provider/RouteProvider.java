package com.hq.backend.provider;

import java.time.Instant;
import java.util.List;

// TRD 11.1. D1 확정: ODsay(경로 탐색) + 카카오맵(지도 렌더). 실 연동은 M1, M0은 계약만 고정한다.
public interface RouteProvider {

    List<RouteOption> search(GeoPoint origin, GeoPoint dest, String anchor, Instant at);
}
