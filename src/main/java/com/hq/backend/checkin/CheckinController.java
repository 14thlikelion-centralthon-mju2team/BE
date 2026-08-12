package com.hq.backend.checkin;

import com.hq.backend.checkin.dto.CheckinRequest;
import com.hq.backend.checkin.dto.CheckinResponse;
import com.hq.backend.common.auth.CurrentUserId;
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
@RequestMapping("/checkins")
@RequiredArgsConstructor
public class CheckinController {

    private final CheckinService checkinService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckinResponse record(@CurrentUserId UUID userId, @Valid @RequestBody CheckinRequest request) {
        return checkinService.record(userId, request);
    }
}
