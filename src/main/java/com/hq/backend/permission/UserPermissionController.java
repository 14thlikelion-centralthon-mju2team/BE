package com.hq.backend.permission;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.permission.dto.PermissionResponse;
import com.hq.backend.permission.dto.PermissionUpdateRequest;
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
@RequestMapping("/me/permissions")
@RequiredArgsConstructor
public class UserPermissionController {
    private final UserPermissionService service;
    @GetMapping public List<PermissionResponse> get(@CurrentUserId UUID userId) { return service.get(userId); }
    @PatchMapping public List<PermissionResponse> update(@CurrentUserId UUID userId, @Valid @RequestBody PermissionUpdateRequest request) {
        return service.update(userId, request);
    }
}
