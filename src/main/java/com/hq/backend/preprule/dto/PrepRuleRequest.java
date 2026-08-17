package com.hq.backend.preprule.dto;

import com.hq.backend.preprule.ActionType;
import com.hq.backend.preprule.RuleCategory;
import com.hq.backend.preprule.RuleTiming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PrepRuleRequest(
        @NotBlank String ruleName,
        @NotNull RuleCategory ruleCategory,
        @NotNull ActionType actionType,
        @NotNull RuleTiming ruleTiming,
        Integer defaultMinutes,
        String applyEventKind,
        String applyTimeBand,
        UUID applyPlaceId,
        String applyWeather,
        boolean isRequired,
        boolean isSensitive,
        boolean fromChip
) {
}
