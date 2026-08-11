package com.hq.backend.calendar.dto;

import jakarta.validation.constraints.NotBlank;

// authCode는 Flutter가 Google Sign-In에서 calendar.readonly 스코프까지 동의받고 받은
// serverAuthCode. 백엔드가 이 코드로 구글과 1회 토큰 교환을 해서 refresh_token을 받는다.
public record ConnectCalendarRequest(@NotBlank String authCode) {
}
