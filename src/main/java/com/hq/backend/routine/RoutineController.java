package com.hq.backend.routine;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.routine.dto.CreateRoutineRequest;
import com.hq.backend.routine.dto.RoutineResponse;
import com.hq.backend.routine.dto.UpdateRoutineRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// GET /today(routine_runs 지연 생성)는 여기 없다 — AI 루틴 초안 생성이 아직 없어서
// 지금 만들어도 실제로 검증할 방법이 없다. AI의 /ai/routines/draft가 준비되면 같이 붙인다.
@RestController
@RequestMapping("/routines")
@RequiredArgsConstructor
public class RoutineController {

    private final RoutineService routineService;

    @GetMapping
    public List<RoutineResponse> list(@CurrentUserId UUID userId) {
        return routineService.listRoutines(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoutineResponse create(@CurrentUserId UUID userId, @Valid @RequestBody CreateRoutineRequest request) {
        return routineService.createRoutine(userId, request);
    }

    @PatchMapping("/{id}")
    public RoutineResponse update(
            @CurrentUserId UUID userId,
            @PathVariable UUID id,
            @RequestBody UpdateRoutineRequest request) {
        return routineService.updateRoutine(userId, id, request);
    }
}
