package com.hq.backend.provider;

import java.time.Instant;

// TRD 11.2. 기상청(단기예보)+에어코리아 조합. 실 연동은 M1, M0은 계약만 고정한다.
// 부재 시 동작(TRD 11.5): 호출부가 예외를 흡수하고 웰니스만 생략, 시간 계획은 정상 진행한다.
public interface EnvironmentProvider {

    EnvironmentSnapshot fetch(GeoPoint point, Instant at);
}
