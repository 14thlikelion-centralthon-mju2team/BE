package com.hq.backend.environment;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.environment.dto.EnvironmentResponse;
import com.hq.backend.place.PlaceCoordinateCodec;
import com.hq.backend.place.UserPlace;
import com.hq.backend.place.UserPlaceRepository;
import com.hq.backend.provider.EnvironmentProvider;
import com.hq.backend.provider.GeoPoint;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 홈 날씨 카드용 공개 조회. 위치는 사용자의 대표 장소를 쓴다 — 기기 위치를 매번 받지
// 않는 대신, PM/UV는 어차피 서버에 설정된 관측소·지역 고정값이라 위치로 달라지는 건
// 기온·하늘 상태뿐이다(기상청 격자 변환).
@RestController
@RequiredArgsConstructor
public class EnvironmentController {

    private final UserPlaceRepository userPlaceRepository;
    private final PlaceCoordinateCodec placeCoordinateCodec;
    private final EnvironmentProvider environmentProvider;

    @GetMapping("/environment/current")
    public EnvironmentResponse current(@CurrentUserId UUID userId) {
        UserPlace place = userPlaceRepository
                .findByUserIdAndIsPrimaryTrueAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PRIMARY_PLACE_NOT_FOUND", "대표 장소가 등록되어 있지 않습니다."));

        GeoPoint point = new GeoPoint(
                placeCoordinateCodec.decode(place.getLatEnc()),
                placeCoordinateCodec.decode(place.getLngEnc()));
        return EnvironmentResponse.from(environmentProvider.fetch(point, Instant.now()));
    }
}
