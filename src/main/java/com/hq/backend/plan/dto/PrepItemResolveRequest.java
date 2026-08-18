package com.hq.backend.plan.dto;

import com.hq.backend.plan.PrepItemCompletionStatus;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

// API 명세 §12.2 — clientEventId는 받되 저장하지 않는다. resolve는 상태를 덮어쓰는
// 연산이라 같은 값으로 재요청해도 자연히 멱등이다(별도 dedup 테이블 불필요).
public record PrepItemResolveRequest(@NotNull PrepItemCompletionStatus completionStatus, UUID clientEventId) {
}
