package com.hq.backend.personalization;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.personalization.dto.PersonalizationResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * 개인화 설정 화면은 특정 일정 문맥이 없으므로 사용자 자신의 global 추정 이력만 되돌린다.
     * estimate와 원본 event를 정석적으로 연결하는 migration 전까지 eventId를 요구하지 않는다.
     */
    @PostMapping("/revert")
    public PersonalizationResponse revert(@CurrentUserId UUID userId) {
        return personalizationService.revert(userId);
    }
}
