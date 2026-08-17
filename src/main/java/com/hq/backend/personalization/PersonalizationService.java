package com.hq.backend.personalization;

import com.hq.backend.personalization.dto.PersonalizationResponse;
import com.hq.backend.personalization.dto.PrepEstimateResponse;
import com.hq.backend.wellness.UserWellnessPrefRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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

    private final UserPrepEstimateRepository userPrepEstimateRepository;
    private final UserWellnessPrefRepository userWellnessPrefRepository;

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
    }
}
