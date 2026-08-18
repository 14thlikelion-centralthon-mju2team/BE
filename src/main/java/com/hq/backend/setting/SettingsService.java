package com.hq.backend.setting;

import com.hq.backend.setting.dto.SettingsRequest;
import com.hq.backend.setting.dto.SettingsResponse;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private final UserSettingRepository userSettingRepository;

    @Transactional(readOnly = true)
    public SettingsResponse get(UUID userId) {
        return userSettingRepository.findById(userId)
                .map(SettingsResponse::from)
                .orElse(SettingsResponse.DEFAULT);
    }

    @Transactional
    public SettingsResponse update(UUID userId, SettingsRequest request) {
        UserSetting setting = userSettingRepository.findById(userId)
                .orElseGet(() -> UserSetting.builder().userId(userId).build());

        setting.setInitialPrepMinutes(request.initialPrepMinutes());
        setting.setArrivalBufferMinutes(request.arrivalBufferMinutes());
        setting.setNotificationSensitivity(request.notificationSensitivity());
        setting.setPersonalizationEnabled(request.personalizationEnabled());
        setting.setAutoManageEnabled(request.autoManageEnabled());
        setting.setWellnessEventEnabled(request.wellnessEventEnabled());
        setting.setLockscreenHideSensitive(request.lockscreenHideSensitive());
        setting.setUpdatedAt(Instant.now());

        return SettingsResponse.from(userSettingRepository.save(setting));
    }
}
