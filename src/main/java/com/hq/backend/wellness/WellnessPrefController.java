package com.hq.backend.wellness;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.wellness.dto.WellnessPrefResponse;
import com.hq.backend.wellness.dto.WellnessPrefsRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me/wellness-prefs")
@RequiredArgsConstructor
public class WellnessPrefController {

    private final WellnessPrefService wellnessPrefService;

    @GetMapping
    public List<WellnessPrefResponse> get(@CurrentUserId UUID userId) {
        return wellnessPrefService.get(userId);
    }

    @PatchMapping
    public List<WellnessPrefResponse> update(@CurrentUserId UUID userId, @Valid @RequestBody WellnessPrefsRequest request) {
        return wellnessPrefService.update(userId, request);
    }
}
