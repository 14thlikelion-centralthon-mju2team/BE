package com.hq.backend.event.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

// API 명세 §8.4의 요청 바디는 questionType/userAnswer뿐이다. reviewId는 클라이언트가
// 굳이 다시 보낼 필요가 없어 선택값으로 둔다 — event당 미답변 리뷰는 부분 유니크
// 인덱스(event_id where answered_at is null)로 최대 1건이라 서버가 결정론적으로 찾는다.
// 보내오면 그 값을 그대로 쓴다(기존 클라이언트 호환).
public record EventReviewRequest(
        UUID reviewId,
        @NotBlank String questionType,
        @NotBlank String userAnswer
) {
}
