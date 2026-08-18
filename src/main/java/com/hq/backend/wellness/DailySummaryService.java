package com.hq.backend.wellness;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.event.EventStatus;
import com.hq.backend.metrics.ProductEvent;
import com.hq.backend.metrics.ProductEventRepository;
import com.hq.backend.personalization.EventExecution;
import com.hq.backend.personalization.EventExecutionRepository;
import com.hq.backend.plan.PlanContext;
import com.hq.backend.plan.PlanContextRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.wellness.dto.DailySummaryResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// API 명세 §12.4 — 하루 마무리 카드. DWL = 0.6*avgWisWeighted + 0.4*avgRls(TRD).
// 한 번 생성되면 그대로 캐시된다(GET 재호출로 다시 계산하지 않음) — isViewed를 덮어쓰지
// 않기 위함. 관리 일정 0건이면 카드를 만들지 않는다(404, 숫자를 지어내지 않는다).
@Service
@RequiredArgsConstructor
public class DailySummaryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final List<String> UNMANAGED_STATUSES = List.of(
            EventStatus.CANCELLED.name().toLowerCase(),
            EventStatus.SKIPPED.name().toLowerCase(),
            EventStatus.UNRESOLVED.name().toLowerCase());

    // ponytail: DWL/카드 시나리오 경계값은 기획 승인 문구가 나오기 전까지 쓰는 임시값.
    // dwlBand 0~39/40~69/70~100 경계만 DB CHECK(ck_summary_dwl_band)로 고정돼 있다.
    private static final int DENSITY_EVENT_COUNT_THRESHOLD = 3;
    private static final int EXPOSURE_OUTDOOR_MINUTES_THRESHOLD = 60;
    private static final int STABLE_DWL_MAX = 39;

    private final EventRepository eventRepository;
    private final PlanRevisionRepository planRevisionRepository;
    private final EventExecutionRepository eventExecutionRepository;
    private final PlanWellnessScoreRepository planWellnessScoreRepository;
    private final PlanContextRepository planContextRepository;
    private final DailyWellnessSummaryRepository dailyWellnessSummaryRepository;
    private final ProductEventRepository productEventRepository;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Transactional
    public DailySummaryResponse getOrGenerate(UUID userId, LocalDate date) {
        return dailyWellnessSummaryRepository.findByUserIdAndSummaryDate(userId, date)
                .map(DailySummaryResponse::from)
                .orElseGet(() -> DailySummaryResponse.from(generate(userId, date)));
    }

    // API 명세 §12.4 — "조회 기록 (지표)". PRODUCT_EVENT는 좌표·제목·민감 항목명을
    // payload에 절대 넣지 않는다(절대 원칙 8) — summaryId/summaryDate만 담는다.
    @Transactional
    public DailySummaryResponse markViewed(UUID userId, UUID summaryId) {
        DailyWellnessSummary summary = dailyWellnessSummaryRepository.findBySummaryIdAndUserId(summaryId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SUMMARY_NOT_FOUND", "일일 요약을 찾을 수 없습니다."));
        summary.setViewed(true);

        Instant now = Instant.now();
        productEventRepository.save(ProductEvent.builder()
                .userId(userId)
                .eventName("card_viewed")
                .occurredAt(now)
                .receivedAt(now)
                .payload(toJson(Map.of("summaryId", summaryId.toString(), "summaryDate", summary.getSummaryDate().toString())))
                .build());

        return DailySummaryResponse.from(summary);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    private DailyWellnessSummary generate(UUID userId, LocalDate date) {
        Instant dayStart = date.atStartOfDay(ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(ZONE).toInstant();

        List<Event> managedEvents = eventRepository
                .findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(userId, dayStart, dayEnd).stream()
                .filter(e -> !UNMANAGED_STATUSES.contains(e.getStatus()))
                .toList();
        if (managedEvents.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SUMMARY_NOT_GENERATED", "해당 날짜에 관리한 일정이 없습니다.");
        }

        List<UUID> eventIds = managedEvents.stream().map(Event::getEventId).toList();
        Map<UUID, EventExecution> executionByEvent = eventExecutionRepository.findAllById(eventIds).stream()
                .collect(Collectors.toMap(EventExecution::getEventId, Function.identity()));

        List<UUID> planIds = managedEvents.stream()
                .flatMap(e -> planRevisionRepository.findByEventIdOrderByRevisionNoDesc(e.getEventId()).stream().findFirst().stream())
                .map(PlanRevision::getPlanId)
                .toList();
        Map<UUID, PlanWellnessScore> scoreByPlan = planWellnessScoreRepository.findAllById(planIds).stream()
                .collect(Collectors.toMap(PlanWellnessScore::getPlanId, Function.identity()));
        Map<UUID, PlanContext> contextByPlan = planContextRepository.findAllById(planIds).stream()
                .collect(Collectors.toMap(PlanContext::getPlanId, Function.identity()));

        Aggregate agg = aggregate(managedEvents, executionByEvent, scoreByPlan, contextByPlan);
        String scenario = pickScenario(managedEvents, executionByEvent, agg);

        return dailyWellnessSummaryRepository.save(DailyWellnessSummary.builder()
                .userId(userId)
                .summaryDate(date)
                .eventCount(managedEvents.size())
                .totalOutdoorMinutes(agg.totalOutdoorMinutes)
                .outdoorSource(agg.allObserved() ? "observed" : "estimated")
                .avgWisWeighted(agg.avgWisWeighted)
                .avgRls(agg.avgRls)
                .dwlScore(agg.dwlScore)
                .dwlBand(bandFor(agg.dwlScore))
                .cardScenario(scenario)
                .cardMessageSnapshot(messageFor(scenario))
                .createdAt(Instant.now())
                .build());
    }

    private record Aggregate(
            int totalOutdoorMinutes, boolean allObserved,
            java.math.BigDecimal avgWisWeighted, java.math.BigDecimal avgRls, short dwlScore) {
    }

    private Aggregate aggregate(
            List<Event> events, Map<UUID, EventExecution> executionByEvent,
            Map<UUID, PlanWellnessScore> scoreByPlan, Map<UUID, PlanContext> contextByPlan) {
        int totalOutdoor = 0;
        boolean hasOutdoorDataPoint = false;
        boolean anyEstimated = false;
        double wisWeightedSum = 0;
        int wisWeightTotal = 0;
        double rlsSum = 0;
        int rlsCount = 0;

        for (Event event : events) {
            EventExecution execution = executionByEvent.get(event.getEventId());
            Optional<PlanRevision> planOpt = planRevisionRepository
                    .findByEventIdOrderByRevisionNoDesc(event.getEventId()).stream().findFirst();
            if (planOpt.isEmpty()) {
                continue;
            }
            UUID planId = planOpt.get().getPlanId();

            Integer outdoorMinutes = null;
            if (execution != null && execution.getActualOutdoorMinutes() != null) {
                outdoorMinutes = execution.getActualOutdoorMinutes();
            } else {
                PlanContext context = contextByPlan.get(planId);
                if (context != null && context.getEstimatedOutdoorMinutes() != null) {
                    outdoorMinutes = context.getEstimatedOutdoorMinutes();
                    anyEstimated = true;
                }
            }
            if (outdoorMinutes != null) {
                hasOutdoorDataPoint = true;
                totalOutdoor += outdoorMinutes;
                PlanWellnessScore score = scoreByPlan.get(planId);
                if (score != null && outdoorMinutes > 0) {
                    wisWeightedSum += score.getWisScore() * outdoorMinutes;
                    wisWeightTotal += outdoorMinutes;
                }
            }
            if (execution != null && execution.getRushLoadScore() != null) {
                rlsSum += execution.getRushLoadScore();
                rlsCount++;
            }
        }

        java.math.BigDecimal avgWis = wisWeightTotal > 0
                ? java.math.BigDecimal.valueOf(wisWeightedSum / wisWeightTotal) : null;
        java.math.BigDecimal avgRls = rlsCount > 0
                ? java.math.BigDecimal.valueOf(rlsSum / rlsCount) : null;

        double wisComponent = avgWis != null ? avgWis.doubleValue() : 0;
        double rlsComponent = avgRls != null ? avgRls.doubleValue() : 0;
        short dwl = (short) Math.round(0.6 * wisComponent + 0.4 * rlsComponent);

        // 데이터가 전혀 없으면(hasOutdoorDataPoint=false) "observed"라고 주장하지 않는다 —
        // 추정치를 관측치처럼 보여주지 않는다는 원칙(API 명세 §12.4)의 연장.
        boolean allObserved = hasOutdoorDataPoint && !anyEstimated;
        return new Aggregate(totalOutdoor, allObserved, avgWis, avgRls, dwl);
    }

    private String pickScenario(List<Event> events, Map<UUID, EventExecution> executionByEvent, Aggregate agg) {
        boolean anyRushedOrLate = events.stream()
                .map(e -> executionByEvent.get(e.getEventId()))
                .filter(java.util.Objects::nonNull)
                .anyMatch(exec -> "rushed".equals(exec.getArrivalResult()) || "late".equals(exec.getArrivalResult()));
        if (anyRushedOrLate) {
            return "rushed";
        }
        if (events.size() >= DENSITY_EVENT_COUNT_THRESHOLD) {
            return "density";
        }
        if (agg.totalOutdoorMinutes >= EXPOSURE_OUTDOOR_MINUTES_THRESHOLD) {
            return "exposure";
        }
        if (agg.dwlScore <= STABLE_DWL_MAX) {
            return "stable";
        }
        return "default";
    }

    private String bandFor(short dwlScore) {
        if (dwlScore <= 39) {
            return "low";
        }
        return dwlScore <= 69 ? "mid" : "high";
    }

    // TR-05·TR-09 — 승인된 템플릿에서만 문구를 고른다(자유 생성 LLM 미사용).
    private String messageFor(String scenario) {
        return switch (scenario) {
            case "rushed" -> "오늘은 이동이 촉박한 순간이 있었어요. 다음 일정은 조금 더 여유를 두고 준비해보는 건 어때요.";
            case "density" -> "오늘은 일정이 많아 바빴어요. 잠시 쉬어가는 시간을 가져보세요.";
            case "exposure" -> "자외선이 높은 시간대의 예상 야외 이동이 길었어요. 지금은 수분을 보충하고 편안하게 쉬어주세요.";
            case "stable" -> "오늘은 무리 없이 안정적으로 하루를 보냈어요.";
            default -> "오늘 하루도 수고하셨어요.";
        };
    }
}
