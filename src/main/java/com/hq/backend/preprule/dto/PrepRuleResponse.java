package com.hq.backend.preprule.dto;

import com.hq.backend.preprule.ActionType;
import com.hq.backend.preprule.RuleCategory;
import com.hq.backend.preprule.RuleTiming;
import com.hq.backend.preprule.UserPrepRule;
import java.util.UUID;

public record PrepRuleResponse(
        UUID prepRuleId,
        String ruleName,
        RuleCategory ruleCategory,
        ActionType actionType,
        RuleTiming ruleTiming,
        Integer defaultMinutes,
        String applyEventKind,
        String applyTimeBand,
        UUID applyPlaceId,
        String applyWeather,
        boolean isRequired,
        boolean isSensitive,
        boolean fromChip
) {

    public static PrepRuleResponse from(UserPrepRule rule) {
        return new PrepRuleResponse(
                rule.getPrepRuleId(),
                rule.getRuleName(),
                RuleCategory.valueOf(rule.getRuleCategory().toUpperCase()),
                ActionType.valueOf(rule.getActionType().toUpperCase()),
                RuleTiming.valueOf(rule.getRuleTiming().toUpperCase()),
                rule.getDefaultMinutes(),
                rule.getApplyEventKind(),
                rule.getApplyTimeBand(),
                rule.getApplyPlaceId(),
                rule.getApplyWeather(),
                rule.isRequired(),
                rule.isSensitive(),
                rule.isFromChip());
    }
}
