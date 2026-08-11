package com.hq.backend.routine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.routine.dto.CreateRoutineRequest;
import com.hq.backend.routine.dto.RoutineResponse;
import com.hq.backend.routine.dto.UpdateRoutineRequest;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoutineServiceTest {

    @Mock private RoutineRepository routineRepository;
    @Mock private RoutineTaskRepository routineTaskRepository;
    @Mock private ActionRepository actionRepository;

    private RoutineService service() {
        return new RoutineService(routineRepository, routineTaskRepository, actionRepository);
    }

    @Test
    void time_스케줄인데_anchorTime이_없으면_거부한다() {
        var request = new CreateRoutineRequest("아침 루틴", "time", null, "FREQ=DAILY", null, List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> service().createRoutine(UUID.randomUUID(), request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_SCHEDULE");
    }

    @Test
    void place_스케줄인데_placeId가_없으면_거부한다() {
        var request = new CreateRoutineRequest("헬스장 루틴", "place", null, null, null, List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> service().createRoutine(UUID.randomUUID(), request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_SCHEDULE");
    }

    @Test
    void 존재하지_않는_action_id가_섞이면_거부한다() {
        UUID validAction = UUID.randomUUID();
        UUID unknownAction = UUID.randomUUID();
        var request = new CreateRoutineRequest(
                "아침 루틴", "time", null, "FREQ=DAILY", LocalTime.of(7, 0), List.of(validAction, unknownAction));

        when(actionRepository.findByIdIn(anyList()))
                .thenReturn(List.of(Action.builder().id(validAction).build())); // unknownAction 누락

        assertThatThrownBy(() -> service().createRoutine(UUID.randomUUID(), request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ACTION_ID");
    }

    @Test
    void 유효한_요청은_루틴과_태스크를_저장한다() {
        UUID userId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        var request = new CreateRoutineRequest(
                "아침 루틴", "time", null, "FREQ=DAILY", LocalTime.of(7, 0), List.of(actionId));

        when(actionRepository.findByIdIn(anyList()))
                .thenReturn(List.of(Action.builder().id(actionId).build()));
        when(routineRepository.save(any(Routine.class))).thenAnswer(invocation -> {
            Routine r = invocation.getArgument(0);
            return Routine.builder()
                    .id(UUID.randomUUID())
                    .userId(r.getUserId())
                    .title(r.getTitle())
                    .scheduleType(r.getScheduleType())
                    .rrule(r.getRrule())
                    .anchorTime(r.getAnchorTime())
                    .createdAt(r.getCreatedAt())
                    .build();
        });
        when(routineTaskRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        RoutineResponse response = service().createRoutine(userId, request);

        assertThat(response.tasks()).hasSize(1);
        assertThat(response.tasks().get(0).actionId()).isEqualTo(actionId);
        assertThat(response.tasks().get(0).orderNo()).isZero();
    }

    @Test
    void 다른_유저의_루틴을_수정하려_하면_404() {
        UUID userId = UUID.randomUUID();
        UUID routineId = UUID.randomUUID();
        when(routineRepository.findByIdAndUserId(routineId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateRoutine(userId, routineId, new UpdateRoutineRequest("새 제목", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "ROUTINE_NOT_FOUND");
    }

    @Test
    void archived_true면_archivedAt이_채워진다() {
        UUID userId = UUID.randomUUID();
        UUID routineId = UUID.randomUUID();
        Routine routine = Routine.builder()
                .id(routineId).userId(userId).title("아침 루틴")
                .scheduleType("time").rrule("FREQ=DAILY").anchorTime(LocalTime.of(7, 0))
                .createdAt(Instant.now())
                .build();
        when(routineRepository.findByIdAndUserId(routineId, userId)).thenReturn(Optional.of(routine));
        when(routineTaskRepository.findByRoutineIdAndArchivedAtIsNullOrderByOrderNo(routineId)).thenReturn(List.of());

        RoutineResponse response = service().updateRoutine(userId, routineId, new UpdateRoutineRequest(null, true));

        assertThat(routine.getArchivedAt()).isNotNull();
        assertThat(response.id()).isEqualTo(routineId);
    }
}
