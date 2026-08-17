package com.hq.backend.preprule.dto;

// 축(ruleCategory/actionType/ruleTiming)과 apply* 조건은 MVP에서 불변 — 바꾸려면 삭제 후
// 재생성한다. PATCH는 엔티티에 setter가 있는 필드만 받는다.
public record PrepRuleUpdateRequest(
        String ruleName,
        Integer defaultMinutes,
        Boolean isRequired,
        Boolean isSensitive
) {
}
