package com.hq.backend.provider;

import java.time.Instant;
import java.util.List;

// TRD 11.1. ODsay 대중교통 경로 API 연동 예정. 응답 스키마(path[].subPath[] 필드 구성)를
// 실제 키로 검증하지 못해 파싱 로직은 아직 없다 — 05-blocked-on-user.md 참고.
// 검증이 끝나면 이 메서드를 채우고 @Component로 등록해 StubRouteProvider를 대체한다.
public class OdsayRouteProvider implements RouteProvider {

    @Override
    public List<RouteOption> search(GeoPoint origin, GeoPoint dest, String anchor, Instant at) {
        throw new UnsupportedOperationException("ODsay 응답 파싱 미구현 — API 키 발급 후 이어서 구현");
    }
}
