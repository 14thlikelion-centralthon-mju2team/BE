package com.hq.backend.bootstrap.dto;

// engine_config 테이블(V6)이 아직 애플리케이션에서 안 쓰여(M1+ Plan/Wellness Engine 붙을 때
// 실제 원격 설정으로 전환) API 명세 예시값을 그대로 반환한다.
public record EngineConfigSummary(String engineVer, String wisVer) {
}
