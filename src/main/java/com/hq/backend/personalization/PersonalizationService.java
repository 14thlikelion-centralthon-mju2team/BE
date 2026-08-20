package com.hq.backend.personalization;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.metrics.ProductEventService;
import com.hq.backend.personalization.dto.PersonalizationResponse;
import com.hq.backend.personalization.dto.PrepEstimateResponse;
import com.hq.backend.wellness.UserWellnessPrefRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// trafficBufferMinutes/notificationLeadMinutes(§15.1)를 저장할 컬럼이 아직 없다
// (user_setting.arrival_buffer_minutes는 의미가 다르고, engine_config는 JPA 엔티티가
// 없다) — 지금은 engine_config 시드값(traffic_buffer_min=5, active_window_lead_min=30)을
// 상수로 반환한다. 개인화 보정이 실제로 이 값을 바꾸게 되면(§15.2) 저장 컬럼을 추가해야 한다.
@Service
@RequiredArgsConstructor
public class PersonalizationService {

    private static final int DEFAULT_TRAFFIC_BUFFER_MINUTES = 5;
    private static final int DEFAULT_NOTIFICATION_LEAD_MINUTES = 30;
    private static final String SCOPE_GLOBAL = "global";

    private final UserPrepEstimateRepository userPrepEstimateRepository;
    private final UserWellnessPrefRepository userWellnessPrefRepository;
    private final ProductEventService productEventService;

    @Transactional(readOnly = true)
    public PersonalizationResponse get(UUID userId) {
        var estimates = userPrepEstimateRepository.findByUserIdAndValidToIsNull(userId).stream()
                .map(PrepEstimateResponse::from)
                .toList();
        return new PersonalizationResponse(estimates, DEFAULT_TRAFFIC_BUFFER_MINUTES, DEFAULT_NOTIFICATION_LEAD_MINUTES);
    }

    @Transactional
    public void reset(UUID userId) {
        // USER_PREP_ESTIMATE는 무효화(valid_to 기록)만 한다 — 하드 삭제하면 과거 계획의
        // 설명가능성(valid_from/valid_to 이력)이 깨진다. EVENT_ACTION_LOG는 건드리지 않는다.
        Instant now = Instant.now();
        userPrepEstimateRepository.findByUserIdAndValidToIsNull(userId)
                .forEach(estimate -> estimate.setValidTo(now));
        userWellnessPrefRepository.deleteByUserId(userId);
        productEventService.record(userId, "personalization_reset", Map.of());
    }

    // 개인화 설정 화면은 특정 일정 문맥 없이 global 스코프의 직전 보정 하나를 되돌린다.
    // 원본 event 제외는 USER_PREP_ESTIMATE에 source event를 저장하는 migration 이후에만 정확히 수행할 수 있다.
    @Transactional
    public PersonalizationResponse revert(UUID userId) {
        List<UserPrepEstimate> history =
                userPrepEstimateRepository.findByUserIdAndScopeTypeOrderByValidFromDesc(userId, SCOPE_GLOBAL);
        if (history.size() < 2) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_ADJUSTMENT_TO_REVERT", "되돌릴 이전 보정이 없습니다.");
        }
        UserPrepEstimate current = history.get(0);
        UserPrepEstimate previous = history.get(1);

        Instant now = Instant.now();
        current.setValidTo(now);
        userPrepEstimateRepository.save(UserPrepEstimate.builder()
                .userId(userId)
                .scopeType(SCOPE_GLOBAL)
                .scopeValue(previous.getScopeValue())
                .estimatedMinutes(previous.getEstimatedMinutes())
                .sampleCount(previous.getSampleCount())
                .confidence(previous.getConfidence())
                .modelVersion(previous.getModelVersion())
                .adjustmentReason("되돌리기 — 이전 값으로 복원")
                .coldStartAdjusted(previous.isColdStartAdjusted())
                .validFrom(now)
                .build());

        productEventService.record(userId, "personalization_reverted", Map.of("scope", SCOPE_GLOBAL));

        return get(userId);
    }
}
