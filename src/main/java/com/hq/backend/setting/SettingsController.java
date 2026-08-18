package com.hq.backend.setting;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.setting.dto.SettingsRequest;
import com.hq.backend.setting.dto.SettingsResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping
    public SettingsResponse get(@CurrentUserId UUID userId) {
        return settingsService.get(userId);
    }

    @PatchMapping
    public SettingsResponse update(@CurrentUserId UUID userId, @Valid @RequestBody SettingsRequest request) {
        return settingsService.update(userId, request);
    }
}
