package com.hq.backend.user;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.common.ratelimit.EndpointRateLimiter;
import com.hq.backend.common.util.TokenHashUtil;
import com.hq.backend.user.dto.AccountDeletionResponse;
import com.hq.backend.user.dto.ChangeNicknameRequest;
import com.hq.backend.user.dto.ChangeNicknameResponse;
import com.hq.backend.user.dto.ChangePasswordRequest;
import com.hq.backend.user.dto.EmailChangeConfirmRequest;
import com.hq.backend.user.dto.EmailChangeRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AccountService accountService;
    private final EndpointRateLimiter rateLimiter;

    @DeleteMapping
    public AccountDeletionResponse withdraw(@CurrentUserId UUID userId) {
        return userService.withdraw(userId);
    }

    @PatchMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@CurrentUserId UUID userId,
                               @Valid @RequestBody ChangePasswordRequest request,
                               @RequestHeader(value = "X-Refresh-Token", required = false)
                               String refreshToken) {
        String refreshTokenHash = refreshToken != null ? TokenHashUtil.sha256(refreshToken) : null;
        accountService.changePassword(userId, request.currentPassword(),
                request.newPassword(), refreshTokenHash);
    }

    @PatchMapping("/nickname")
    public ChangeNicknameResponse changeNickname(@CurrentUserId UUID userId,
                                                 @Valid @RequestBody ChangeNicknameRequest request) {
        return accountService.changeNickname(userId, request.nickname());
    }

    @PostMapping("/email/change-request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestEmailChange(@CurrentUserId UUID userId,
                                   @Valid @RequestBody EmailChangeRequest request,
                                   HttpServletRequest httpRequest) {
        String ip = httpRequest.getHeader("X-Real-IP");
        if (ip == null) ip = httpRequest.getRemoteAddr();
        if (!rateLimiter.tryAcquire(ip + ":email-change", 3, 600)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");
        }
        accountService.requestEmailChange(userId, request.newEmail(), request.password());
    }

    @PostMapping("/email/change-confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmEmailChange(@Valid @RequestBody EmailChangeConfirmRequest request) {
        accountService.confirmEmailChange(request.token());
    }
}
