package com.hq.backend.personalization;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.personalization.dto.PersonalizationResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me/personalization")
@RequiredArgsConstructor
public class PersonalizationController {

    private final PersonalizationService personalizationService;

    @GetMapping
    public PersonalizationResponse get(@CurrentUserId UUID userId) {
        return personalizationService.get(userId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@CurrentUserId UUID userId) {
        personalizationService.reset(userId);
    }
}
