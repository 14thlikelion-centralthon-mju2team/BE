package com.hq.backend.user;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.user.dto.AccountDeletionResponse;
import com.hq.backend.user.dto.ChangeNicknameRequest;
import com.hq.backend.user.dto.ChangeNicknameResponse;
import com.hq.backend.user.dto.ChangePasswordRequest;
import com.hq.backend.user.dto.EmailChangeConfirmRequest;
import com.hq.backend.user.dto.EmailChangeRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
        String refreshTokenHash = refreshToken != null ? hashToken(refreshToken) : null;
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
                                   @Valid @RequestBody EmailChangeRequest request) {
        accountService.requestEmailChange(userId, request.newEmail(), request.password());
    }

    @PostMapping("/email/change-confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmEmailChange(@Valid @RequestBody EmailChangeConfirmRequest request) {
        accountService.confirmEmailChange(request.token());
    }

    private String hashToken(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
