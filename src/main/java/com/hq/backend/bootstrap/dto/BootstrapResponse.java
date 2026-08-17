package com.hq.backend.bootstrap.dto;

import java.util.List;

public record BootstrapResponse(
        SettingsSummary settings,
        List<Object> permissions,
        List<PlaceSummary> places,
        List<Object> prepItems,
        Object todayPlan,
        EngineConfigSummary engineConfig
) {
}
