package com.hq.backend.common.auth;

import com.hq.backend.auth.JwtService;
import com.hq.backend.common.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

// 이 리졸버 하나가 지금 이 프로젝트의 인증 검사 전부다 — 토큰 서명·만료만 확인하고
// 어떤 라우트를 보호할지는 각 컨트롤러가 @CurrentUserId를 붙였는지로만 결정된다.
// 전역 필터가 없으니 이 파라미터를 빼먹은 엔드포인트는 그냥 인증 없이 열려버린다.
// 라우트 단위 강제(빼먹으면 기동 실패 등)는 setting/security 티켓에서 다룰 문제.
@Component
@RequiredArgsConstructor
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final JwtService jwtService;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && parameter.getParameterType().equals(UUID.class);
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String header = request == null ? null : request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "인증이 필요합니다.");
        }

        return jwtService.getUserId(header.substring("Bearer ".length()));
    }
}
