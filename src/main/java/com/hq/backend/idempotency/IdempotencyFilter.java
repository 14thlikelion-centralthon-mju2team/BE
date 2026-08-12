package com.hq.backend.idempotency;

import com.hq.backend.auth.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

// #28 — Idempotency-Key 헤더가 있는 POST 요청만 대상. 헤더를 안 보내면 기존 동작 그대로라
// 이미 머지된 엔드포인트(/routines, /consents, /places, /events)는 영향 없음 — 프론트가
// 이 헤더를 실제로 보내기 시작하는 엔드포인트부터 자연히 적용된다. 소급 적용은 안 함(#28).
//
// ponytail: 응답 저장(store)이 컨트롤러의 @Transactional과 같은 트랜잭션이 아니다 — 필터는
// 서블릿 레벨이라 컨트롤러가 이미 커밋한 *다음*에 응답을 가로챈다. 그 사이 아주 좁은 창에서
// 크래시가 나면 원본 작업은 성공했는데 idempotency 기록은 안 남을 수 있다(재시도 시 중복
// 재실행 가능). 완벽한 원자성이 필요해지면 서비스 트랜잭션 안에서 직접 기록하는 방식으로
// 승격.
@Component
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "Idempotency-Key";

    private final IdempotencyService idempotencyService;
    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String idempotencyKey = request.getHeader(HEADER_NAME);
        if (idempotencyKey == null || idempotencyKey.isBlank() || !"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<UUID> userId = extractUserId(request);
        if (userId.isEmpty()) {
            // 토큰이 없거나 유효하지 않으면 멱등성 처리를 건너뛰고 평소대로 진행 — 인증
            // 실패 응답은 CurrentUserArgumentResolver가 정상적으로 처리한다.
            filterChain.doFilter(request, response);
            return;
        }

        String endpoint = request.getMethod() + " " + request.getRequestURI();
        Optional<IdempotencyRecord> existing = idempotencyService.find(userId.get(), idempotencyKey, endpoint);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            response.setStatus(record.getStatusCode());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(record.getResponseBody());
            return;
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrappedResponse);

        // 2xx만 저장 — 실패 응답(예: 일시적 DB 오류로 인한 500)까지 캐시해버리면 재시도해도
        // 영원히 같은 실패가 재생된다.
        if (wrappedResponse.getStatus() >= 200 && wrappedResponse.getStatus() < 300) {
            String body = new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
            idempotencyService.store(userId.get(), idempotencyKey, endpoint, wrappedResponse.getStatus(), body);
        }
        wrappedResponse.copyBodyToResponse(); // 캐싱 래퍼는 이걸 안 하면 실제 응답이 안 나간다.
    }

    private Optional<UUID> extractUserId(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        try {
            return Optional.of(jwtService.getUserId(header.substring("Bearer ".length())));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
