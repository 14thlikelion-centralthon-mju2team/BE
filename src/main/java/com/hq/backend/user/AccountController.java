package com.hq.backend.user;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.user.dto.LinkProviderRequest;
import com.hq.backend.user.dto.ProviderResponse;
import com.hq.backend.user.dto.SessionResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class AccountController {

    private final AccountManagementService accountManagementService;

    // ─── Providers ───

    @GetMapping("/providers")
    public List<ProviderResponse> listProviders(@CurrentUserId UUID userId) {
        return accountManagementService.listProviders(userId);
    }

    @PostMapping("/providers")
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderResponse linkProvider(@CurrentUserId UUID userId,
                                         @Valid @RequestBody LinkProviderRequest request) {
        return accountManagementService.linkProvider(userId, request);
    }

    @DeleteMapping("/providers/{identityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkProvider(@CurrentUserId UUID userId, @PathVariable UUID identityId) {
        accountManagementService.unlinkProvider(userId, identityId);
    }

    // ─── Sessions ───

    @GetMapping("/sessions")
    public List<SessionResponse> listSessions(
            @CurrentUserId UUID userId,
            @RequestHeader(value = "X-Refresh-Token", required = false) String refreshToken) {
        return accountManagementService.listSessions(userId, refreshToken);
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(
            @CurrentUserId UUID userId,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Refresh-Token", required = false) String refreshToken) {
        accountManagementService.revokeSession(userId, id, refreshToken);
    }

    @DeleteMapping("/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeOtherSessions(
            @CurrentUserId UUID userId,
            @RequestHeader(value = "X-Refresh-Token", required = false) String refreshToken) {
        accountManagementService.revokeOtherSessions(userId, refreshToken);
    }

    // ─── Action Logs ───

    @DeleteMapping("/action-logs")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteActionLogs(@CurrentUserId UUID userId) {
        accountManagementService.deleteActionLogs(userId);
    }
}
