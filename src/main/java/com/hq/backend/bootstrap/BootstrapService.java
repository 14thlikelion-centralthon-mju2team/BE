package com.hq.backend.bootstrap;

import com.hq.backend.bootstrap.dto.BootstrapResponse;
import com.hq.backend.bootstrap.dto.EngineConfigSummary;
import com.hq.backend.bootstrap.dto.PlaceSummary;
import com.hq.backend.bootstrap.dto.SettingsSummary;
import com.hq.backend.place.PlaceCoordinateCodec;
import com.hq.backend.place.UserPlaceRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// M0 최소 범위 — places만 실제 저장 데이터를 읽고, settings/engineConfig는 아직 없는
// UserSetting/EngineConfig 엔티티 대신 V6 마이그레이션 기본값·API 명세 예시값을 반환한다.
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

    public BootstrapResponse bootstrap(UUID userId) {
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

        return new BootstrapResponse(
                DEFAULT_SETTINGS,
                List.of(),
                places,
                List.of(),
                null,
                DEFAULT_ENGINE_CONFIG);
    }
}
