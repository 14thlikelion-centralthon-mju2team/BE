package com.hq.backend.adjustment;

import com.hq.backend.adjustment.dto.AdjustmentResponse;
import com.hq.backend.adjustment.dto.CreateAdjustmentRequest;
import com.hq.backend.common.auth.CurrentUserId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// red_signal·streak_up 트리거 조정은 AI 서버가 별도 DB 롤로 adjustments에 직접 INSERT한다
// (TRD 6.7/138, setting/security Phase 6에서 롤 분리) — 여기는 그 경로가 아니다. 이 컨트롤러는
// 사용자가 직접 요청하는 조정(user_manual)만 다룬다.
@RestController
@RequestMapping("/adjustments")
@RequiredArgsConstructor
public class AdjustmentController {

    private final AdjustmentService adjustmentService;

    @GetMapping
    public List<AdjustmentResponse> list(@CurrentUserId UUID userId) {
        return adjustmentService.listAdjustments(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdjustmentResponse create(@CurrentUserId UUID userId, @Valid @RequestBody CreateAdjustmentRequest request) {
        return adjustmentService.createAdjustment(userId, request);
    }
}
