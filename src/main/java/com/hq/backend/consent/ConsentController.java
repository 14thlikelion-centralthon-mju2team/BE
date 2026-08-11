package com.hq.backend.consent;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.consent.dto.ConsentRequest;
import com.hq.backend.consent.dto.ConsentResponse;
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
@RequestMapping("/consents")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentService consentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentResponse record(@CurrentUserId UUID userId, @Valid @RequestBody ConsentRequest request) {
        return consentService.record(userId, request);
    }
}
