package com.hq.backend.adjustment;

import com.hq.backend.adjustment.dto.AdjustmentResponse;
import com.hq.backend.adjustment.dto.CreateAdjustmentRequest;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.routine.Action;
import com.hq.backend.routine.ActionRepository;
import com.hq.backend.routine.Routine;
import com.hq.backend.routine.RoutineRepository;
import com.hq.backend.routine.RoutineTask;
import com.hq.backend.routine.RoutineTaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// adjustments INSERT의 실제 효과(routine_tasks.action_id 갱신, 소유권·ladder·낙관적 잠금 검증)는
// 전부 DB 트리거 trg_apply_adjustment가 처리한다(V1__init.sql). 여기서는 그 검증을 앱
// 레벨에서 먼저 해서 흔한 케이스는 깔끔한 4xx로 응답하고, 트리거는 최종 안전망으로만 쓴다 —
// 이 서비스 안에서 read-then-insert 사이에 진짜 레이스가 나면 트리거가 여전히 막아준다.
@Slf4j
@Service
@RequiredArgsConstructor
public class AdjustmentService {

    private static final String TRIGGER_TYPE_USER_MANUAL = "user_manual";

    private final AdjustmentRepository adjustmentRepository;
    private final RoutineTaskRepository routineTaskRepository;
    private final RoutineRepository routineRepository;
    private final ActionRepository actionRepository;

    @Transactional(readOnly = true)
    public List<AdjustmentResponse> listAdjustments(UUID userId) {
        return adjustmentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AdjustmentResponse createAdjustment(UUID userId, CreateAdjustmentRequest request) {
        RoutineTask routineTask = findOwnedRoutineTask(userId, request.routineTaskId());

        if (request.beforeActionId().equals(request.afterActionId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_ACTION", "beforeActionId와 afterActionId가 같습니다.");
        }
        if (!routineTask.getActionId().equals(request.beforeActionId())) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ADJUSTMENT",
                    "beforeActionId가 현재 상태와 다릅니다. 최신 상태를 다시 조회해주세요.");
        }

        Action before = findAction(request.beforeActionId());
        Action after = findAction(request.afterActionId());
        if (!before.getLadderKey().equals(after.getLadderKey())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LADDER_MISMATCH", "같은 계열(ladder)의 action끼리만 조정할 수 있습니다.");
        }

        try {
            Adjustment saved = adjustmentRepository.save(Adjustment.builder()
                    .userId(userId)
                    .routineTaskId(request.routineTaskId())
                    .beforeActionId(request.beforeActionId())
                    .afterActionId(request.afterActionId())
                    .triggerType(TRIGGER_TYPE_USER_MANUAL)
                    .reason(request.reason())
                    .createdAt(Instant.now())
                    .build());
            return toResponse(saved);
        } catch (DataAccessException e) {
            // 위에서 다 확인했는데도 여기서 실패한다면 지금은 read-then-insert 사이의 진짜
            // 레이스(stale adjustment)뿐이지만, trg_apply_adjustment는 그 외에도 3가지를 더
            // raise한다(routine_task not found·user_id mismatch·ladder mismatch,
            // V1__init.sql apply_adjustment()). 사전 검증 순서가 바뀌거나 트리거에 케이스가
            // 추가되면 다른 원인이 여기로 와서 STALE_ADJUSTMENT로 잘못 보고될 수 있다 —
            // 응답 코드는 그대로 두되 실제 원인은 로그에 남겨서 나중에 추적 가능하게 한다.
            log.warn("adjustments INSERT가 트리거에서 거부됨 (userId={}, routineTaskId={}): {}",
                    userId, request.routineTaskId(), e.getMessage(), e);
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ADJUSTMENT",
                    "beforeActionId가 현재 상태와 다릅니다. 최신 상태를 다시 조회해주세요.");
        }
    }

    private RoutineTask findOwnedRoutineTask(UUID userId, UUID routineTaskId) {
        RoutineTask routineTask = routineTaskRepository.findById(routineTaskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROUTINE_TASK_NOT_FOUND", "루틴 태스크를 찾을 수 없습니다."));
        Routine routine = routineRepository.findByIdAndUserId(routineTask.getRoutineId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROUTINE_TASK_NOT_FOUND", "루틴 태스크를 찾을 수 없습니다."));
        // RoutineRepository.findByIdAndUserId엔 archived 필터가 없다(RoutineService.updateRoutine이
        // archived 루틴을 다시 찾아서 복구해야 하므로 공용 메서드엔 필터를 걸 수 없음) — 여기서
        // 별도로 확인한다. 삭제된 루틴의 태스크는 조정 대상이 아니다.
        if (routine.getArchivedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROUTINE_TASK_NOT_FOUND", "루틴 태스크를 찾을 수 없습니다.");
        }
        if (routineTask.getArchivedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROUTINE_TASK_NOT_FOUND", "루틴 태스크를 찾을 수 없습니다.");
        }
        return routineTask;
    }

    private Action findAction(UUID actionId) {
        return actionRepository.findById(actionId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACTION_ID", "존재하지 않는 action_id입니다."));
    }

    private AdjustmentResponse toResponse(Adjustment adjustment) {
        return new AdjustmentResponse(
                adjustment.getId(),
                adjustment.getRoutineTaskId(),
                adjustment.getBeforeActionId(),
                adjustment.getAfterActionId(),
                adjustment.getTriggerType(),
                adjustment.getReason(),
                adjustment.getCreatedAt());
    }
}
