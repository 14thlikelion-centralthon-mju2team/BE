package com.hq.backend.gapcheck;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.gapcheck.dto.GapCheckRequest;
import com.hq.backend.gapcheck.dto.GapCheckResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/gap-checks")
@RequiredArgsConstructor
public class GapCheckController {

    private final GapCheckService gapCheckService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GapCheckResponse record(@CurrentUserId UUID userId, @Valid @RequestBody GapCheckRequest request) {
        return gapCheckService.record(userId, request);
    }
}
