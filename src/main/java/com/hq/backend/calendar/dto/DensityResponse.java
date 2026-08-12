package com.hq.backend.calendar.dto;

import java.util.List;

// calendarSynced=false는 "이번 호출에서 구글 연동을 시도했는데 실패했다"는 뜻이다 — 구글 연동
// 자체가 없는 사용자는 실패가 아니라 synced=true(할 게 없었을 뿐)로 온다. "오늘 일정이 진짜
// 없다"와 "연동이 끊겨서 못 가져왔다"를 클라이언트가 구분할 수 있게 하기 위함(#29 피드백).
public record DensityResponse(boolean calendarSynced, List<BusyBlockResponse> blocks) {
}
