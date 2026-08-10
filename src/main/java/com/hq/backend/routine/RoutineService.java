package com.hq.backend.routine;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.routine.dto.CreateRoutineRequest;
import com.hq.backend.routine.dto.RoutineResponse;
import com.hq.backend.routine.dto.RoutineTaskResponse;
import com.hq.backend.routine.dto.UpdateRoutineRequest;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoutineService {

    private static final String SCHEDULE_TIME = "time";
    private static final String SCHEDULE_PLACE = "place";

    private final RoutineRepository routineRepository;
    private final RoutineTaskRepository routineTaskRepository;
    private final ActionRepository actionRepository;

    @Transactional(readOnly = true)
    public List<RoutineResponse> listRoutines(UUID userId) {
        List<Routine> routines = routineRepository.findByUserIdAndArchivedAtIsNull(userId);
        List<UUID> routineIds = routines.stream().map(Routine::getId).toList();

        Map<UUID, List<RoutineTask>> tasksByRoutine = routineTaskRepository
                .findByRoutineIdInAndArchivedAtIsNull(routineIds)
                .stream()
                .collect(Collectors.groupingBy(RoutineTask::getRoutineId));

        return routines.stream()
                .map(routine -> toResponse(routine, tasksByRoutine.getOrDefault(routine.getId(), List.of())))
                .toList();
    }

    @Transactional
    public RoutineResponse createRoutine(UUID userId, CreateRoutineRequest request) {
        validateSchedule(request.scheduleType(), request.placeId(), request.rrule(), request.anchorTime());

        // AI가 뱉은 action_id가 활성 라이브러리에 있어야 한다는 계약(§7 공통 검증) —
        // 허용목록에 없는 ID가 하나라도 섞여 있으면 통째로 거부한다.
        List<Action> actions = actionRepository.findByIdIn(request.actionIds());
        if (actions.size() != request.actionIds().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACTION_ID", "존재하지 않는 action_id가 포함되어 있습니다.");
        }

        Routine routine = routineRepository.save(Routine.builder()
                .userId(userId)
                .placeId(request.placeId())
                .title(request.title())
                .scheduleType(request.scheduleType())
                .rrule(request.rrule())
                .anchorTime(request.anchorTime())
                .createdAt(Instant.now())
                .build());

        List<RoutineTask> tasks = saveTasks(routine.getId(), request.actionIds());

        return toResponse(routine, tasks);
    }

    @Transactional
    public RoutineResponse updateRoutine(UUID userId, UUID routineId, UpdateRoutineRequest request) {
        Routine routine = routineRepository.findByIdAndUserId(routineId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROUTINE_NOT_FOUND", "루틴을 찾을 수 없습니다."));

        // 관리 상태(managed) 엔티티라 JPA dirty checking으로 커밋 시점에 자동 반영된다.
        // save()를 따로 안 불러도 된다 — 다만 반환값에 즉시 최신 상태를 담기 위해 그대로 둔다.
        if (request.title() != null) {
            routine.setTitle(request.title());
        }
        if (request.archived() != null) {
            routine.setArchivedAt(request.archived() ? Instant.now() : null);
        }

        List<RoutineTask> tasks = routineTaskRepository.findByRoutineIdAndArchivedAtIsNullOrderByOrderNo(routine.getId());
        return toResponse(routine, tasks);
    }

    private List<RoutineTask> saveTasks(UUID routineId, List<UUID> actionIds) {
        List<RoutineTask> tasks = new ArrayList<>();
        for (int i = 0; i < actionIds.size(); i++) {
            tasks.add(RoutineTask.builder()
                    .routineId(routineId)
                    .actionId(actionIds.get(i))
                    .orderNo(i)
                    .createdAt(Instant.now())
                    .build());
        }
        return routineTaskRepository.saveAll(tasks);
    }

    private void validateSchedule(String scheduleType, UUID placeId, String rrule, LocalTime anchorTime) {
        boolean valid = switch (scheduleType) {
            case SCHEDULE_PLACE -> placeId != null;
            case SCHEDULE_TIME -> rrule != null && anchorTime != null;
            default -> false;
        };
        if (!valid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SCHEDULE",
                    "scheduleType은 'time'(rrule+anchorTime 필수) 또는 'place'(placeId 필수)여야 합니다.");
        }
    }

    private RoutineResponse toResponse(Routine routine, List<RoutineTask> tasks) {
        List<RoutineTaskResponse> taskResponses = tasks.stream()
                .sorted(Comparator.comparingInt(RoutineTask::getOrderNo))
                .map(t -> new RoutineTaskResponse(t.getId(), t.getActionId(), t.getOrderNo()))
                .toList();

        return new RoutineResponse(
                routine.getId(),
                routine.getTitle(),
                routine.getScheduleType(),
                routine.getPlaceId(),
                routine.getRrule(),
                routine.getAnchorTime(),
                taskResponses,
                routine.getCreatedAt()
        );
    }
}
