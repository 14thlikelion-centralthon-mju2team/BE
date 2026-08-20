package com.hq.backend.common.ratelimit;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 인메모리 슬라이딩 윈도우 rate limiter.
 * SMTP abuse 방어용 — nginx 전역 30r/s와 별개로 민감 엔드포인트에 적용.
 * 단일 VM 전제 (TRD T1). 다중 인스턴스 전환 시 Redis로 교체.
 *
 * Refs #208 P2: password reset, verification resend, email change에 적용.
 */
@Component
public class EndpointRateLimiter {

    private static final int DEFAULT_MAX_REQUESTS = 5;
    private static final int DEFAULT_WINDOW_SECONDS = 300; // 5분

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    /**
     * 요청을 허용할지 판단한다. 허용하면 카운터를 증가시키고 true 반환.
     * @param key IP 또는 "IP:endpoint" 복합 키
     */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, DEFAULT_MAX_REQUESTS, DEFAULT_WINDOW_SECONDS);
    }

    public boolean tryAcquire(String key, int maxRequests, int windowSeconds) {
        Instant now = Instant.now();
        WindowCounter counter = counters.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired(now, windowSeconds)) {
                return new WindowCounter(now);
            }
            return existing;
        });
        return counter.incrementIfAllowed(maxRequests);
    }

    private static class WindowCounter {
        private final Instant windowStart;
        private final AtomicInteger count = new AtomicInteger(0);

        WindowCounter(Instant windowStart) {
            this.windowStart = windowStart;
        }

        boolean isExpired(Instant now, int windowSeconds) {
            return now.isAfter(windowStart.plusSeconds(windowSeconds));
        }

        boolean incrementIfAllowed(int max) {
            int current = count.incrementAndGet();
            return current <= max;
        }
    }
}
