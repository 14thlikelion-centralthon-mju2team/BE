package com.hq.backend.preprule;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.preprule.dto.PrepRuleRequest;
import com.hq.backend.preprule.dto.PrepRuleResponse;
import com.hq.backend.preprule.dto.PrepRuleUpdateRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PrepRuleService {

    private final UserPrepRuleRepository userPrepRuleRepository;

    @Transactional(readOnly = true)
    public List<PrepRuleResponse> list(UUID userId) {
        return userPrepRuleRepository.findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(userId).stream()
                .map(PrepRuleResponse::from)
                .toList();
    }

    @Transactional
    public PrepRuleResponse create(UUID userId, PrepRuleRequest request) {
        validateMinutesRule(request.actionType(), request.defaultMinutes());
        if (request.fromChip() && request.isSensitive()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SENSITIVE_CHIP_REJECTED",
                    "민감·규제 품목은 추천 칩으로 등록할 수 없습니다.");
        }
        boolean isSensitive = request.ruleCategory() == RuleCategory.MEDICATION || request.isSensitive();

        UserPrepRule saved = userPrepRuleRepository.save(UserPrepRule.builder()
                .userId(userId)
                .ruleName(request.ruleName())
                .ruleCategory(request.ruleCategory().name().toLowerCase())
                .actionType(request.actionType().name().toLowerCase())
                .ruleTiming(request.ruleTiming().name().toLowerCase())
                .defaultMinutes(request.defaultMinutes())
                .applyEventKind(request.applyEventKind())
                .applyTimeBand(request.applyTimeBand())
                .applyPlaceId(request.applyPlaceId())
                .applyWeather(request.applyWeather())
                .isRequired(request.isRequired())
                .isSensitive(isSensitive)
                .fromChip(request.fromChip())
                .isActive(true)
                .createdAt(Instant.now())
                .build());

        return PrepRuleResponse.from(saved);
    }

    @Transactional
    public PrepRuleResponse update(UUID userId, UUID prepRuleId, PrepRuleUpdateRequest request) {
        UserPrepRule rule = findOwned(userId, prepRuleId);

        ActionType actionType = ActionType.valueOf(rule.getActionType().toUpperCase());
        Integer defaultMinutes = request.defaultMinutes() != null ? request.defaultMinutes() : rule.getDefaultMinutes();
        validateMinutesRule(actionType, defaultMinutes);

        if (request.ruleName() != null) {
            rule.setRuleName(request.ruleName());
        }
        if (request.defaultMinutes() != null) {
            rule.setDefaultMinutes(request.defaultMinutes());
        }
        if (request.isRequired() != null) {
            rule.setRequired(request.isRequired());
        }
        if (request.isSensitive() != null) {
            rule.setSensitive(request.isSensitive());
        }

        // create()와 동일한 서버 검증 규칙 ②③을 PATCH에도 재적용 — isSensitive만 따로
        // 갱신할 수 있다 보니 이 재검증이 없으면 medication 항목의 잠금화면 마스킹을
        // 끄거나(③), fromChip 항목을 사후에 민감으로 바꿔 금지 조합(②)을 만들 수 있었다.
        RuleCategory ruleCategory = RuleCategory.valueOf(rule.getRuleCategory().toUpperCase());
        if (ruleCategory == RuleCategory.MEDICATION) {
            rule.setSensitive(true);
        }
        if (rule.isFromChip() && rule.isSensitive()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SENSITIVE_CHIP_REJECTED",
                    "민감·규제 품목은 추천 칩으로 등록할 수 없습니다.");
        }

        return PrepRuleResponse.from(rule);
    }

    @Transactional
    public void delete(UUID userId, UUID prepRuleId) {
        UserPrepRule rule = findOwned(userId, prepRuleId);
        rule.setActive(false);
        rule.setDeletedAt(Instant.now());
    }

    private UserPrepRule findOwned(UUID userId, UUID prepRuleId) {
        return userPrepRuleRepository.findByPrepRuleIdAndUserId(prepRuleId, userId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PREP_RULE_NOT_FOUND", "준비 규칙을 찾을 수 없습니다."));
    }

    private void validateMinutesRule(ActionType actionType, Integer defaultMinutes) {
        boolean timedRoutine = actionType == ActionType.TIMED_ROUTINE;
        if (timedRoutine != (defaultMinutes != null)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "actionType이 timed_routine일 때만 defaultMinutes를 지정할 수 있습니다.");
        }
    }
}
