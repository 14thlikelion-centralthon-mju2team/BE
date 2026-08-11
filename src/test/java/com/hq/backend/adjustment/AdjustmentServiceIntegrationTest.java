package com.hq.backend.adjustment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hq.backend.adjustment.dto.CreateAdjustmentRequest;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.routine.Action;
import com.hq.backend.routine.ActionRepository;
import com.hq.backend.routine.Routine;
import com.hq.backend.routine.RoutineRepository;
import com.hq.backend.routine.RoutineTask;
import com.hq.backend.routine.RoutineTaskRepository;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;

// trg_apply_adjustment(V1__init.sql)가 실제로 routine_tasks.action_id를 갱신하는지, 그리고
// 낙관적 잠금(stale 케이스)이 실제 Postgres에서 어떤 예외로 나오는지는 Mockito로는 검증 불가 —
// 진짜 DB로 확인한다.
@SpringBootTest
class AdjustmentServiceIntegrationTest {

    @Autowired private AdjustmentService adjustmentService;
    @Autowired private AdjustmentRepository adjustmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoutineRepository routineRepository;
    @Autowired private RoutineTaskRepository routineTaskRepository;
    @Autowired private ActionRepository actionRepository;

    @Test
    void 조정_생성하면_트리거가_routine_task의_action_id를_실제로_바꾼다() {
        UUID userId = createUser();
        String ladderKey = randomLadderKey();
        Action easy = createAction(ladderKey, 1);
        Action hard = createAction(ladderKey, 2);
        RoutineTask task = createRoutineTask(userId, easy.getId());

        var response = adjustmentService.createAdjustment(
                userId, new CreateAdjustmentRequest(task.getId(), easy.getId(), hard.getId(), "테스트 사유"));

        assertThat(response.triggerType()).isEqualTo("user_manual");
        RoutineTask reloaded = routineTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getActionId()).isEqualTo(hard.getId());
    }

    @Test
    void ladder_key가_다르면_거부한다() {
        UUID userId = createUser();
        Action easy = createAction(randomLadderKey(), 1);
        Action otherLadder = createAction(randomLadderKey(), 1);
        RoutineTask task = createRoutineTask(userId, easy.getId());

        assertThatThrownBy(() -> adjustmentService.createAdjustment(
                        userId, new CreateAdjustmentRequest(task.getId(), easy.getId(), otherLadder.getId(), "테스트 사유")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "LADDER_MISMATCH");
    }

    @Test
    void beforeActionId가_현재_상태와_다르면_거부한다() {
        UUID userId = createUser();
        String ladderKey = randomLadderKey();
        Action a = createAction(ladderKey, 1);
        Action b = createAction(ladderKey, 2);
        Action c = createAction(ladderKey, 3);
        RoutineTask task = createRoutineTask(userId, a.getId()); // 현재 실제 action은 a

        assertThatThrownBy(() -> adjustmentService.createAdjustment(
                        userId, new CreateAdjustmentRequest(task.getId(), b.getId(), c.getId(), "테스트 사유")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "STALE_ADJUSTMENT");
    }

    // 앱 레벨 사전 검증을 건너뛰고 리포지토리로 직접 stale INSERT를 시도 — 트리거의 낙관적 잠금
    // UPDATE가 0행이라 실제로 예외를 던지는지, Hibernate가 그걸 DataAccessException 계열로
    // 번역하는지(AdjustmentService의 catch 절이 실제로 잡을 수 있는 타입인지) 확인.
    @Test
    void 트리거_자체가_stale_INSERT를_거부하는지_직접_확인() {
        UUID userId = createUser();
        String ladderKey = randomLadderKey();
        Action a = createAction(ladderKey, 1);
        Action b = createAction(ladderKey, 2);
        Action c = createAction(ladderKey, 3);
        RoutineTask task = createRoutineTask(userId, a.getId()); // 실제 현재값은 a

        assertThatThrownBy(() -> adjustmentRepository.save(Adjustment.builder()
                        .userId(userId)
                        .routineTaskId(task.getId())
                        .beforeActionId(b.getId()) // 실제 현재값(a)과 다름 — stale
                        .afterActionId(c.getId())
                        .triggerType("user_manual")
                        .reason("트리거 직접 확인")
                        .createdAt(Instant.now())
                        .build()))
                .isInstanceOf(DataAccessException.class);
    }

    // RoutineRepository.findByIdAndUserId는 archived 필터가 없다(RoutineService.updateRoutine이
    // archived 루틴을 복구할 때 이 메서드로 다시 찾아야 해서) — AdjustmentService가 별도로
    // archived 여부를 확인하는지 검증.
    @Test
    void 삭제된_루틴의_태스크에는_조정이_거부된다() {
        UUID userId = createUser();
        String ladderKey = randomLadderKey();
        Action easy = createAction(ladderKey, 1);
        Action hard = createAction(ladderKey, 2);
        Routine routine = routineRepository.save(Routine.builder()
                .userId(userId)
                .title("삭제된 루틴")
                .scheduleType("time")
                .rrule("FREQ=DAILY")
                .anchorTime(LocalTime.of(7, 0))
                .archivedAt(Instant.now()) // 이미 삭제된 상태
                .createdAt(Instant.now())
                .build());
        RoutineTask task = routineTaskRepository.save(RoutineTask.builder()
                .routineId(routine.getId())
                .actionId(easy.getId())
                .orderNo(0)
                .createdAt(Instant.now())
                .build());

        assertThatThrownBy(() -> adjustmentService.createAdjustment(
                        userId, new CreateAdjustmentRequest(task.getId(), easy.getId(), hard.getId(), "삭제된 루틴 테스트")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "ROUTINE_TASK_NOT_FOUND");
    }

    private UUID createUser() {
        String suffix = UUID.randomUUID().toString();
        return userRepository.save(User.builder()
                        .provider("email")
                        .providerUid("adjustment-test-" + suffix)
                        .email("adjustment-test-" + suffix + "@example.com")
                        .passwordHash("test-hash")
                        .nickname("adjustment-tester")
                        .timezone("Asia/Seoul")
                        .createdAt(Instant.now())
                        .build())
                .getId();
    }

    // ladder_key는 (ladder_key, difficulty) 유니크 제약이 걸려있고, 이 테스트들은 트랜잭션
    // 롤백 없이 진짜 DB에 커밋한다(다른 통합 테스트들과 동일) — 재실행 시 충돌 안 나게 매번
    // 랜덤 접미사를 붙인다.
    private String randomLadderKey() {
        return "test-ladder-" + UUID.randomUUID();
    }

    private Action createAction(String ladderKey, int difficulty) {
        return actionRepository.save(Action.builder()
                .category("focus")
                .ladderKey(ladderKey)
                .difficulty(difficulty)
                .estMinutes(10)
                .title("테스트 action " + ladderKey + "-" + difficulty)
                .build());
    }

    private RoutineTask createRoutineTask(UUID userId, UUID actionId) {
        Routine routine = routineRepository.save(Routine.builder()
                .userId(userId)
                .title("테스트 루틴")
                .scheduleType("time")
                .rrule("FREQ=DAILY")
                .anchorTime(LocalTime.of(7, 0))
                .createdAt(Instant.now())
                .build());
        return routineTaskRepository.save(RoutineTask.builder()
                .routineId(routine.getId())
                .actionId(actionId)
                .orderNo(0)
                .createdAt(Instant.now())
                .build());
    }
}
