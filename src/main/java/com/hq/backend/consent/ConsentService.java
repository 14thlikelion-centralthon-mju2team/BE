package com.hq.backend.consent;

import com.hq.backend.consent.dto.ConsentRequest;
import com.hq.backend.consent.dto.ConsentResponse;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// ERD v3 전환에 맞춘 최소 수정 — user_consents(agreed:bool) -> user_consent(action:문자열,
// idempotencyKey 필수) 매핑만 바꿨다. TODO(박찬): API 명세 §2.8은 idempotencyKey를
// 클라이언트가 보내 재제출 시 이력 중복을 막는데, 지금 ConsentRequest엔 그 필드가 없어
// 서버에서 임시로 매 요청마다 새로 발급한다 — 즉 지금은 진짜 멱등성이 없다. 클라이언트
// 요청 스키마를 API 명세대로 확장할 때 같이 고쳐야 한다.
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final UserConsentRepository userConsentRepository;

    @Transactional
    public ConsentResponse record(UUID userId, ConsentRequest request) {
        UserConsent saved = userConsentRepository.save(UserConsent.builder()
                .userId(userId)
                .consentType(request.consentType().name().toLowerCase())
                .policyVersion(request.policyVersion())
                .action(request.agreed() ? "agreed" : "revoked")
                .isRequired(false)
                .idempotencyKey(UUID.randomUUID())
                .recordedAt(Instant.now())
                .build());

        return new ConsentResponse(
                saved.getConsentEventId(),
                ConsentType.valueOf(saved.getConsentType().toUpperCase()),
                "agreed".equals(saved.getAction()),
                saved.getRecordedAt());
    }
}
