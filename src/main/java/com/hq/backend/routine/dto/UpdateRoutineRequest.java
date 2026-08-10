package com.hq.backend.routine.dto;

// PATCH 부분수정: null인 필드는 그대로 두고, 값이 있는 필드만 반영한다.
// archived=true면 archivedAt을 지금 시각으로, false면 다시 null로 되돌린다.
public record UpdateRoutineRequest(String title, Boolean archived) {
}
