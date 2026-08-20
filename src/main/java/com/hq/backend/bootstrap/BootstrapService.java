package com.hq.backend.bootstrap;

import com.hq.backend.bootstrap.dto.BootstrapResponse;
import com.hq.backend.bootstrap.dto.EngineConfigSummary;
import com.hq.backend.bootstrap.dto.PlaceSummary;
import com.hq.backend.bootstrap.dto.SettingsSummary;
import com.hq.backend.bootstrap.dto.UserSummary;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.permission.UserPermissionRepository;
import com.hq.backend.permission.dto.PermissionResponse;
import com.hq.backend.place.PlaceCoordinateCodec;
import com.hq.backend.place.UserPlaceRepository;
import com.hq.backend.setting.UserSettingRepository;
import com.hq.backend.user.UserRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

// M0 최소 범위 — places/settings는 실제 저장 데이터를 읽고, engineConfig는 아직 없는
// EngineConfig 엔티티 대신 API 명세 예시값을 반환한다.
// permissions/prepItems/todayPlan은 각각 M1+ 도메인이라 항상 빈 값.
@Service
@RequiredArgsConstructor
public class BootstrapService {

    private static final SettingsSummary DEFAULT_SETTINGS =
            new SettingsSummary(null, 10, "normal", false, true);
    private static final EngineConfigSummary DEFAULT_ENGINE_CONFIG =
            new EngineConfigSummary("2.1.0", "w1");

    private final UserPlaceRepository userPlaceRepository;
    private final PlaceCoordinateCodec placeCoordinateCodec;
    private final UserSettingRepository userSettingRepository;
    private final UserRepository userRepository;
    private final UserPermissionRepository userPermissionRepository;

    public BootstrapResponse bootstrap(UUID userId) {
        UserSummary user = userRepository.findById(userId)
                .map(found -> new UserSummary(
                        found.getUserId().toString(),
                        found.getNickname(),
                        found.getTimezone(),
                        found.getAccountStatus()))
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));

        SettingsSummary settings = userSettingRepository.findById(userId)
                .map(s -> new SettingsSummary(
                        s.getInitialPrepMinutes(),
                        s.getArrivalBufferMinutes(),
                        s.getNotificationSensitivity(),
                        s.isWellnessEventEnabled(),
                        s.isLockscreenHideSensitive()))
                .orElse(DEFAULT_SETTINGS);

        List<PlaceSummary> places = userPlaceRepository.findByUserIdAndDeletedAtIsNull(userId).stream()
                .map(place -> new PlaceSummary(
                        place.getPlaceId(),
                        place.getPlaceType(),
                        place.getPlaceName(),
                        place.getAddress(),
                        placeCoordinateCodec.decode(place.getLatEnc()),
                        placeCoordinateCodec.decode(place.getLngEnc()),
                        place.isPrimary()))
                .toList();

        List<PermissionResponse> permissions = userPermissionRepository.findByIdUserId(userId).stream()
                .map(PermissionResponse::from)
                .toList();

        return new BootstrapResponse(
                user,
                settings,
                permissions,
                places,
                List.of(),
                null,
                DEFAULT_ENGINE_CONFIG);
    }
}
