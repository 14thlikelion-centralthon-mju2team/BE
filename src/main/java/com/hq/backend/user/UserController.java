package com.hq.backend.user;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.user.dto.AccountDeletionResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @DeleteMapping
    public AccountDeletionResponse withdraw(@CurrentUserId UUID userId) {
        return userService.withdraw(userId);
    }
}
