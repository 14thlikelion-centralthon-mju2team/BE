package com.hq.backend.wellness;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.event.EventStatus;
import com.hq.backend.personalization.EventExecution;
import com.hq.backend.personalization.EventExecutionRepository;
import com.hq.backend.plan.PlanContext;
import com.hq.backend.plan.PlanContextRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.wellness.dto.WeeklySummaryResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주간 리포트(CAL-06) 집계. 일일 요약과 달리 저장 테이블을 두지 않고 조회 시 계산한다 —
 * 화면 진입 빈도가 낮고, 주 중간에 실행 기록이 채워지면 값이 따라 움직여야 하기 때문이다.
 *
 * <p>"관리 일정"과 야외 노출 산출 규칙은 {@link DailySummaryService}와 같은 정의를 쓴다.
 * 두 화면이 같은 주를 다르게 세면 안 된다.</p>
 */
@Service
@RequiredArgsConstructor
public class WeeklySummaryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String ARRIVAL_ON_TIME = "on_time";
    private static final String ARRIVAL_UNKNOWN = "unknown";
    private static final String COMPLETION_COMPLETED = "completed";

    private final EventRepository eventRepository;
    private final EventExecutionRepository eventExecutionRepository;
    private final PlanRevisionRepository planRevisionRepository;
    private final PlanContextRepository planContextRepository;
    private final PlanWellnessActionRepository planWellnessActionRepository;

    @Transactional(readOnly = true)
    public WeeklySummaryResponse get(UUID userId, LocalDate date) {
        LocalDate weekStart = date.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);
        Instant from = weekStart.atStartOfDay(ZONE).toInstant();
        Instant to = weekEnd.plusDays(1).atStartOfDay(ZONE).toInstant();

        List<Event> events = eventRepository
                .findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(userId, from, to).stream()
                .filter(event -> isManagedStatus(event.getStatus()))
                .toList();

        // 일정이 하나도 없어도 404로 막지 않는다 — 리포트 화면은 "관리 일정 0건"을
        // 보여줘야 하고, 0은 지어낸 숫자가 아니라 사실이다.
        if (events.isEmpty()) {
            return empty(weekStart, weekEnd);
        }

        List<UUID> eventIds = events.stream().map(Event::getEventId).toList();
        Map<UUID, EventExecution> executionByEvent = eventExecutionRepository.findAllById(eventIds).stream()
                .collect(Collectors.toMap(EventExecution::getEventId, Function.identity()));
        Map<UUID, PlanRevision> latestPlanByEvent = planRevisionRepository.findByEventIdIn(eventIds).stream()
                .collect(Collectors.groupingBy(
                        PlanRevision::getEventId,
                        Collectors.collectingAndThen(
                                Collectors.maxBy(Comparator.comparingInt(PlanRevision::getRevisionNo)),
                                Optional::orElseThrow)));
        List<UUID> planIds = latestPlanByEvent.values().stream().map(PlanRevision::getPlanId).toList();
        Map<UUID, PlanContext> contextByPlan = planContextRepository.findAllById(planIds).stream()
                .collect(Collectors.toMap(PlanContext::getPlanId, Function.identity()));

        Arrival arrival = arrival(events, executionByEvent);
        Outdoor outdoor = outdoor(events, executionByEvent, latestPlanByEvent, contextByPlan);
        Wellness wellness = wellness(planIds);

        return new WeeklySummaryResponse(
                weekStart,
                weekEnd,
                events.size(),
                arrival.onTimeRate(),
                arrival.onTimeSampleCount(),
                arrival.averageSlackMinutes(),
                arrival.slackSampleCount(),
                prepAccuracy(events, executionByEvent, latestPlanByEvent),
                wellness.completionRate(),
                wellness.proposedCount(),
                wellness.completedCount(),
                outdoor.minutes(),
                outdoor.sampleCount(),
                outdoor.source());
    }

    private WeeklySummaryResponse empty(LocalDate weekStart, LocalDate weekEnd) {
        return new WeeklySummaryResponse(
                weekStart, weekEnd, 0, null, 0, null, 0, List.of(), null, 0, 0, 0, 0, "estimated");
    }

    /**
     * 정시 도착률과 평균 여유.
     *
     * <p>정시는 {@code on_time}만 센다. {@code early}는 분모에는 들어가지만 정시로 세지 않는다 —
     * 너무 일찍 도착한 것도 계획이 빗나간 것이기 때문이다. {@code unknown}은 결과를 모르는
     * 것이므로 분모에서 빠진다.</p>
     *
     * <p>여유는 일정 시작 시각에서 실제 도착 시각을 뺀 분이다. 지각이면 음수로 남긴다.</p>
     */
    private Arrival arrival(List<Event> events, Map<UUID, EventExecution> executionByEvent) {
        int onTime = 0;
        int arrivalSamples = 0;
        long slackSum = 0;
        int slackSamples = 0;

        for (Event event : events) {
            EventExecution execution = executionByEvent.get(event.getEventId());
            if (execution == null) {
                continue;
            }
            String result = execution.getArrivalResult();
            if (result != null && !ARRIVAL_UNKNOWN.equals(result)) {
                arrivalSamples++;
                if (ARRIVAL_ON_TIME.equals(result)) {
                    onTime++;
                }
            }
            if (execution.getActualArrivedAt() != null) {
                slackSum += Duration.between(execution.getActualArrivedAt(), event.getStartsAt()).toMinutes();
                slackSamples++;
            }
        }

        return new Arrival(
                arrivalSamples == 0 ? null : (double) onTime / arrivalSamples,
                arrivalSamples,
                slackSamples == 0 ? null : (int) Math.round((double) slackSum / slackSamples),
                slackSamples);
    }

    /**
     * 일별 준비 시간 예측 대비 실제. 예측은 계획이 안내한 준비 구간(prepStartAt~recommendedDepartAt),
     * 실제는 사용자가 기록한 준비 시작~완료 구간이다. 둘 다 있는 일정만 센다.
     */
    private List<WeeklySummaryResponse.PrepAccuracyPoint> prepAccuracy(
            List<Event> events,
            Map<UUID, EventExecution> executionByEvent,
            Map<UUID, PlanRevision> latestPlanByEvent) {
        Map<LocalDate, long[]> byDate = new TreeMap<>();

        for (Event event : events) {
            EventExecution execution = executionByEvent.get(event.getEventId());
            PlanRevision plan = latestPlanByEvent.get(event.getEventId());
            if (execution == null || plan == null
                    || execution.getActualPrepStartedAt() == null || execution.getActualPrepFinishedAt() == null) {
                continue;
            }
            long predicted = Duration.between(plan.getPrepStartAt(), plan.getRecommendedDepartAt()).toMinutes();
            long actual = Duration.between(
                    execution.getActualPrepStartedAt(), execution.getActualPrepFinishedAt()).toMinutes();

            long[] bucket = byDate.computeIfAbsent(
                    event.getStartsAt().atZone(ZONE).toLocalDate(), key -> new long[3]);
            bucket[0] += predicted;
            bucket[1] += actual;
            bucket[2] += 1;
        }

        List<WeeklySummaryResponse.PrepAccuracyPoint> points = new ArrayList<>();
        byDate.forEach((date, bucket) -> points.add(new WeeklySummaryResponse.PrepAccuracyPoint(
                date,
                (int) Math.round((double) bucket[0] / bucket[2]),
                (int) Math.round((double) bucket[1] / bucket[2]),
                (int) bucket[2])));
        return points;
    }

    /** 제안한 웰니스 행동 중 완료 비율. 아직 응답하지 않은(proposed) 것도 분모에 들어간다. */
    private Wellness wellness(List<UUID> planIds) {
        if (planIds.isEmpty()) {
            return new Wellness(null, 0, 0);
        }
        List<PlanWellnessAction> actions = planWellnessActionRepository.findByPlanIdIn(planIds);
        if (actions.isEmpty()) {
            return new Wellness(null, 0, 0);
        }
        int completed = (int) actions.stream()
                .filter(action -> COMPLETION_COMPLETED.equals(action.getCompletionStatus()))
                .count();
        return new Wellness((double) completed / actions.size(), actions.size(), completed);
    }

    /**
     * 야외 노출 합계. 일일 요약과 같은 규칙으로, 실측(actualOutdoorMinutes)이 있으면 그걸 쓰고
     * 없으면 계획 시점의 추정치를 쓴다. 하나라도 추정치가 섞이면 estimated다.
     */
    private Outdoor outdoor(
            List<Event> events,
            Map<UUID, EventExecution> executionByEvent,
            Map<UUID, PlanRevision> latestPlanByEvent,
            Map<UUID, PlanContext> contextByPlan) {
        int total = 0;
        int samples = 0;
        boolean anyEstimated = false;

        for (Event event : events) {
            PlanRevision plan = latestPlanByEvent.get(event.getEventId());
            if (plan == null) {
                continue;
            }
            EventExecution execution = executionByEvent.get(event.getEventId());
            Integer minutes = null;
            if (execution != null && execution.getActualOutdoorMinutes() != null) {
                minutes = execution.getActualOutdoorMinutes();
            } else {
                PlanContext context = contextByPlan.get(plan.getPlanId());
                if (context != null && context.getEstimatedOutdoorMinutes() != null) {
                    minutes = context.getEstimatedOutdoorMinutes();
                    anyEstimated = true;
                }
            }
            if (minutes != null) {
                total += minutes;
                samples++;
            }
        }

        // 데이터가 없으면 observed라고 주장하지 않는다(일일 요약 §12.4와 같은 원칙).
        return new Outdoor(total, samples, samples > 0 && !anyEstimated ? "observed" : "estimated");
    }

    private boolean isManagedStatus(String value) {
        if (value == null) {
            return true;
        }
        try {
            return switch (EventStatus.valueOf(value.toUpperCase(Locale.ROOT))) {
                case CANCELLED, SKIPPED, UNRESOLVED -> false;
                default -> true;
            };
        } catch (IllegalArgumentException ignored) {
            return true;
        }
    }

    private record Arrival(
            Double onTimeRate, int onTimeSampleCount, Integer averageSlackMinutes, int slackSampleCount) {
    }

    private record Wellness(Double completionRate, int proposedCount, int completedCount) {
    }

    private record Outdoor(int minutes, int sampleCount, String source) {
    }
}
