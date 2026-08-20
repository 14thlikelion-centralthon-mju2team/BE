package com.hq.backend.bootstrap.dto;

// API 명세 §3. 프로필·계정 화면이 nickname을 여기서 읽는다.
public record UserSummary(String userId, String nickname, String timezone, String accountStatus) {
}
