package com.hq.backend.bootstrap.dto;

import com.hq.backend.permission.dto.PermissionResponse;
import java.util.List;

public record BootstrapResponse(
        UserSummary user,
        SettingsSummary settings,
        List<PermissionResponse> permissions,
        List<PlaceSummary> places,
        List<Object> prepItems,
        Object todayPlan,
        EngineConfigSummary engineConfig
) {
}
