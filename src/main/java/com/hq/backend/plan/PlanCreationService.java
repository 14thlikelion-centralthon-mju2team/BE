package com.hq.backend.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.hq.backend.event.Event;
import com.hq.backend.personalization.UserPrepEstimateRepository;
import com.hq.backend.place.PlaceCoordinateCodec;
import com.hq.backend.place.UserPlace;
import com.hq.backend.place.UserPlaceRepository;
import com.hq.backend.plan.dto.PlanEngineRequest;
import com.hq.backend.plan.dto.PlanEngineResponse;
import com.hq.backend.preprule.RuleTiming;
import com.hq.backend.preprule.UserPrepRule;
import com.hq.backend.preprule.UserPrepRuleRepository;
import com.hq.backend.provider.EnvironmentProvider;
import com.hq.backend.provider.GeoPoint;
import com.hq.backend.provider.RouteProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// POST /events(§8.2)의 계획 자동 생성과 §9.4/9.5/10.2의 재계산 공용 진입점. ai/plan-engine(#88)이
// 계산만 하고, 경로·환경 조회와 DB 저장은 여기서 한다. 원점(originPlaceId)이나 목적지 좌표, 경로
// 후보, 엔진 응답 중 하나라도 없으면 조용히 empty를 반환한다 — 계획 없이도 일정 생성 자체는
// 항상 성공해야 한다(TR-11.5와 같은 원칙).
@Service
@RequiredArgsConstructor
public class PlanCreationService {

    // engine_config 시드값(V6). 개인화가 이 값을 실제로 조정하게 되면(§15.2) 그때 저장 컬럼을
    // 만들어 옮긴다 — 지금은 PersonalizationService의 기본값과 같은 상수를 그대로 쓴다.
    private static final int SEED_FALLBACK_MINUTES = 30;
    private static final int RAIN_THRESHOLD_PERCENT = 60;
    private static final int RAIN_EXTRA_PREP_MINUTES = 5;
    private static final int ARRIVAL_BUFFER_MINUTES = 10;
    private static final int TRAFFIC_BUFFER_MINUTES = 5;
    private static final String ANCHOR_ARRIVE_BY = "arrive_by";

    private final PlanEngineClient planEngineClient;
    private final PlanRevisionRepository planRevisionRepository;
    private final PlanContextRepository planContextRepository;
    private final RouteOptionRepository routeOptionRepository;
    private final PlanPrepItemRepository planPrepItemRepository;
    private final UserPrepRuleRepository userPrepRuleRepository;
    private final UserPrepEstimateRepository userPrepEstimateRepository;
    private final UserPlaceRepository userPlaceRepository;
    private final PlaceCoordinateCodec placeCoordinateCodec;
    private final RouteProvider routeProvider;
    private final EnvironmentProvider environmentProvider;

    // 이 앱엔 자동 설정된 ObjectMapper 빈이 없다(Boot 4.1 웹 스타터 구성상 JSON
    // 자동설정이 빈으로 노출되지 않음) — reasons/degraded 직렬화용으로 직접 만든다.
    // 필드명이 전부 한 단어(field/source/text 등)라 네이밍 전략 차이는 안 생긴다.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public Optional<PlanRevision> createInitialPlan(UUID userId, Event event, UUID originPlaceId) {
        return compute(userId, event, originPlaceId, null)
                .map(computed -> persist(computed, 1));
    }

    // §9.4 재계산 · §9.5 사용자 수정 · §10.2 경로 재선택의 공용 진입점.
    // routeTypeOverride가 있으면 새로 조회한 후보 중 같은 routeType을 우선 선택한다
    // ("선택은 재계산을 동반한다"). previousInputHash가 새로 계산한 값과 같으면
    // 저장 없이 changed=false를 반환한다(§5.5 — 외부 API 재호출은 이미 했지만 엔진 결과가
    // 같으므로 리비전은 만들지 않는다. TRD가 말하는 "호출 0회"는 이상값이고, 실제로는
    // Route/Environment Provider가 전부 스텁이라 지금은 비용 차이가 없다).
    @Transactional
    public RecomputeResult recompute(
            UUID userId, Event event, UUID originPlaceId, int nextRevisionNo,
            String previousInputHash, String routeTypeOverride) {
        Optional<ComputedPlan> computedOpt = compute(userId, event, originPlaceId, routeTypeOverride);
        if (computedOpt.isEmpty()) {
            return new RecomputeResult(Optional.empty(), false);
        }
        ComputedPlan computed = computedOpt.get();
        if (previousInputHash != null && previousInputHash.equals(computed.inputHash())) {
            return new RecomputeResult(Optional.empty(), false);
        }
        PlanRevision saved = persist(computed, nextRevisionNo);
        return new RecomputeResult(Optional.of(saved), true);
    }

    public record RecomputeResult(Optional<PlanRevision> revision, boolean changed) {
    }

    private record ComputedPlan(
            UUID eventId, UUID originPlaceId, String originName, double originLat, double originLng,
            List<com.hq.backend.provider.RouteOption> routes,
            com.hq.backend.provider.RouteOption selectedRoute,
            com.hq.backend.provider.EnvironmentSnapshot environment,
            PlanEngineResponse output,
            String inputHash,
            Instant computedAt) {
    }

    private Optional<ComputedPlan> compute(UUID userId, Event event, UUID originPlaceId, String routeTypeOverride) {
        if (event.getDestinationLat() == null || event.getDestinationLng() == null || originPlaceId == null) {
            return Optional.empty();
        }
        UserPlace origin = userPlaceRepository.findByPlaceIdAndUserId(originPlaceId, userId).orElse(null);
        if (origin == null) {
            return Optional.empty();
        }

        double originLat = placeCoordinateCodec.decode(origin.getLatEnc());
        double originLng = placeCoordinateCodec.decode(origin.getLngEnc());
        GeoPoint originPoint = new GeoPoint(originLat, originLng);
        GeoPoint destPoint = new GeoPoint(event.getDestinationLat(), event.getDestinationLng());
        Instant now = Instant.now();

        List<com.hq.backend.provider.RouteOption> routes =
                routeProvider.search(originPoint, destPoint, ANCHOR_ARRIVE_BY, now);
        if (routes.isEmpty()) {
            return Optional.empty();
        }
        com.hq.backend.provider.RouteOption selectedRoute = routes.stream()
                .filter(r -> routeTypeOverride != null && routeTypeOverride.equals(r.rank()))
                .findFirst()
                .orElse(routes.get(0));
        com.hq.backend.provider.EnvironmentSnapshot environment = fetchEnvironmentSafely(destPoint, now);

        PlanEngineRequest engineRequest = buildEngineRequest(userId, event, selectedRoute, environment, now);
        Optional<PlanEngineResponse> engineResponse = planEngineClient.compute(engineRequest);
        if (engineResponse.isEmpty()) {
            return Optional.empty();
        }
        PlanEngineResponse output = engineResponse.get();
        String inputHash = computeInputHash(event, originPlaceId, selectedRoute, environment, output);

        return Optional.of(new ComputedPlan(
                event.getEventId(), originPlaceId, origin.getPlaceName(), originLat, originLng,
                routes, selectedRoute, environment, output, inputHash, now));
    }

    private PlanRevision persist(ComputedPlan computed, int revisionNo) {
        PlanEngineResponse output = computed.output();
        PlanRevision revision = planRevisionRepository.save(PlanRevision.builder()
                .eventId(computed.eventId())
                .revisionNo(revisionNo)
                .originPlaceId(computed.originPlaceId())
                .originSnapshotName(computed.originName())
                .originSnapshotLat(computed.originLat())
                .originSnapshotLng(computed.originLng())
                .prepStartAt(output.prepStartAt())
                .recommendedDepartAt(output.recommendedDepartAt())
                .targetArriveAt(output.targetArriveAt())
                .estimatedPrepMinutes(output.breakdown().estimatedPrepMinutes())
                .extraPrepMinutes(output.breakdown().extraPrepMinutes())
                .personalRoutineMinutes(output.breakdown().personalRoutineMinutes())
                .travelMinutes(output.breakdown().travelMinutes())
                .trafficBufferMinutes(output.breakdown().trafficBufferMinutes())
                .arrivalBufferMinutes(output.breakdown().arrivalBufferMinutes())
                .feasible(output.feasible())
                .reasons(toJson(output.reasons()))
                .degraded(toJson(output.degraded()))
                .predictionConfidence(output.predictionConfidence())
                .planStatus("active")
                .calcVersion(output.calcVersion())
                .inputHash(computed.inputHash())
                .createdAt(computed.computedAt())
                .build());

        persistRouteOptions(revision, computed.routes(), computed.selectedRoute());
        persistEnvironmentContext(revision, computed.environment());
        persistChecklist(revision, output.checklist());

        return revision;
    }

    private void persistRouteOptions(
            PlanRevision revision, List<com.hq.backend.provider.RouteOption> routes,
            com.hq.backend.provider.RouteOption selected) {
        int rank = 1;
        for (com.hq.backend.provider.RouteOption route : routes) {
            RouteOption saved = routeOptionRepository.save(RouteOption.builder()
                    .planId(revision.getPlanId())
                    .routeRank(rank)
                    .routeType(route.rank())
                    .totalMinutes(route.totalSec() / 60)
                    .walkMinutes(route.walkSec() / 60)
                    .transferCount(route.transfers())
                    .departAt(route.departAt())
                    .arriveAt(route.etaAt())
                    .routePayload(toJson(route.rawRef()))
                    .build());
            if (route == selected) {
                revision.setSelectedRouteOptionId(saved.getRouteOptionId());
            }
            rank++;
        }
    }

    private void persistEnvironmentContext(PlanRevision revision, com.hq.backend.provider.EnvironmentSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        planContextRepository.save(PlanContext.builder()
                .planId(revision.getPlanId())
                .temperature(java.math.BigDecimal.valueOf(snapshot.tempC()))
                .precipitationProb(java.math.BigDecimal.valueOf(snapshot.precipitationProb()))
                .uvIndex((short) Math.round(snapshot.uvIndex()))
                .pm10(snapshot.pm10())
                .weatherProvider(snapshot.provider())
                .observedAt(snapshot.asOf())
                .build());
    }

    // TRD §11.5 — 환경 제공자 실패는 웰니스만 생략시키고 시간 계획은 그대로 진행한다.
    private com.hq.backend.provider.EnvironmentSnapshot fetchEnvironmentSafely(GeoPoint point, Instant now) {
        try {
            return environmentProvider.fetch(point, now);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void persistChecklist(PlanRevision revision, List<PlanEngineResponse.ChecklistItem> checklist) {
        for (PlanEngineResponse.ChecklistItem item : checklist) {
            planPrepItemRepository.save(PlanPrepItem.builder()
                    .planId(revision.getPlanId())
                    .itemNameSnapshot(item.itemName())
                    .actionTypeSnapshot(item.actionType())
                    .appliedMinutes(item.appliedMinutes())
                    .isSensitive(item.isSensitive())
                    .sourceType(item.sourceType())
                    .reasonSnapshot(item.reason())
                    .completionStatus("pending")
                    .build());
        }
    }

    private PlanEngineRequest buildEngineRequest(
            UUID userId, Event event, com.hq.backend.provider.RouteOption selectedRoute,
            com.hq.backend.provider.EnvironmentSnapshot environment, Instant now) {
        PlanEngineRequest.PrepEstimateSnapshot prepEstimate = userPrepEstimateRepository
                .findByUserIdAndValidToIsNull(userId).stream()
                .filter(e -> "global".equals(e.getScopeType()))
                .findFirst()
                .map(e -> new PlanEngineRequest.PrepEstimateSnapshot(
                        e.getEstimatedMinutes(), e.getModelVersion(), e.getSampleCount()))
                .orElse(null);

        List<PlanEngineRequest.PrepItemSnapshot> prepItems = userPrepRuleRepository
                .findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(userId).stream()
                .filter(rule -> !RuleTiming.POST_ARRIVAL.name().toLowerCase().equals(rule.getRuleTiming()))
                .map(this::toPrepItemSnapshot)
                .toList();

        PlanEngineRequest.EnvironmentSnapshot environmentSnapshot = environment == null ? null
                : new PlanEngineRequest.EnvironmentSnapshot(
                        environment.precipitationProb(), null, environment.asOf());

        return new PlanEngineRequest(
                now,
                new PlanEngineRequest.EventSnapshot(event.getStartsAt(), ANCHOR_ARRIVE_BY, null),
                prepEstimate,
                ARRIVAL_BUFFER_MINUTES,
                TRAFFIC_BUFFER_MINUTES,
                new PlanEngineRequest.RouteSnapshot(
                        selectedRoute.id(), selectedRoute.totalSec() / 60, selectedRoute.walkSec() / 60,
                        selectedRoute.provider(), false),
                environmentSnapshot,
                prepItems,
                new PlanEngineRequest.EngineConfig(
                        SEED_FALLBACK_MINUTES, RAIN_THRESHOLD_PERCENT, RAIN_EXTRA_PREP_MINUTES,
                        ARRIVAL_BUFFER_MINUTES, TRAFFIC_BUFFER_MINUTES));
    }

    private PlanEngineRequest.PrepItemSnapshot toPrepItemSnapshot(UserPrepRule rule) {
        boolean timedRoutine = "timed_routine".equals(rule.getActionType());
        return new PlanEngineRequest.PrepItemSnapshot(
                rule.getPrepRuleId().toString(),
                rule.getRuleName(),
                rule.getActionType(),
                "rule",
                timedRoutine && rule.getDefaultMinutes() != null ? rule.getDefaultMinutes() : 0,
                rule.isSensitive());
    }

    // TRD §5.5 inputHash 계약의 실용 버전 — calcVersion/weightVersion과 quantize(context) 전체
    // 구간표는 아직 없어(웰니스 엔진 M3 미착수) 강수 여부 하나만 RAIN_THRESHOLD_PERCENT 경계로
    // 양자화하고 나머지 입력을 그대로 이어붙인다. 목적(같은 입력이면 새 리비전을 안 만든다)은
    // 그대로 달성된다.
    private String computeInputHash(
            Event event, UUID originPlaceId, com.hq.backend.provider.RouteOption route,
            com.hq.backend.provider.EnvironmentSnapshot environment, PlanEngineResponse output) {
        String rainBucket = environment != null && environment.precipitationProb() >= RAIN_THRESHOLD_PERCENT
                ? "rain_high" : "rain_low";
        String raw = String.join("|",
                event.getStartsAt().toString(),
                String.valueOf(originPlaceId),
                String.valueOf(event.getDestinationLat()),
                String.valueOf(event.getDestinationLng()),
                route.rank(),
                String.valueOf(route.totalSec() / 60),
                String.valueOf(route.walkSec() / 60),
                rainBucket,
                String.valueOf(output.breakdown().estimatedPrepMinutes()),
                String.valueOf(output.breakdown().personalRoutineMinutes()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
