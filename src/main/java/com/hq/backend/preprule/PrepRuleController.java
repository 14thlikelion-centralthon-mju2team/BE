package com.hq.backend.preprule;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.preprule.dto.PrepRuleRequest;
import com.hq.backend.preprule.dto.PrepRuleResponse;
import com.hq.backend.preprule.dto.PrepRuleUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/prep-items")
@RequiredArgsConstructor
public class PrepRuleController {

    private final PrepRuleService prepRuleService;

    @GetMapping
    public List<PrepRuleResponse> list(@CurrentUserId UUID userId) {
        return prepRuleService.list(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PrepRuleResponse create(@CurrentUserId UUID userId, @Valid @RequestBody PrepRuleRequest request) {
        return prepRuleService.create(userId, request);
    }

    @PatchMapping("/{prepRuleId}")
    public PrepRuleResponse update(
            @CurrentUserId UUID userId,
            @PathVariable UUID prepRuleId,
            @RequestBody PrepRuleUpdateRequest request) {
        return prepRuleService.update(userId, prepRuleId, request);
    }

    @DeleteMapping("/{prepRuleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID prepRuleId) {
        prepRuleService.delete(userId, prepRuleId);
    }
}
