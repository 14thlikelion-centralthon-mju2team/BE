package com.hq.backend.common.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Authorization: Bearer 헤더의 userId를 컨트롤러 파라미터로 바로 받기 위한 마커.
// 왜 Spring Security @AuthenticationPrincipal이 아니라 직접 만들었는지: 아직 이 프로젝트에
// SecurityFilterChain 자체가 없다(setting/security, Phase 6, 백A 담당). 그게 들어오면
// CurrentUserArgumentResolver는 지우고 표준 방식으로 옮기면 된다.
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {
}
