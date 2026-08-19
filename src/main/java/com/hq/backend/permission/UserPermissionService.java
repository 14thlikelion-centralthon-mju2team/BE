package com.hq.backend.permission;

import com.hq.backend.permission.dto.PermissionResponse;
import com.hq.backend.permission.dto.PermissionUpdateRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserPermissionService {
    private final UserPermissionRepository repository;

    @Transactional(readOnly = true)
    public List<PermissionResponse> get(UUID userId) {
        return repository.findByIdUserId(userId).stream().map(PermissionResponse::from).toList();
    }

    @Transactional
    public List<PermissionResponse> update(UUID userId, PermissionUpdateRequest request) {
        Instant now = Instant.now();
        for (PermissionUpdateRequest.PermissionItem item : request.permissions()) {
            UserPermissionId id = new UserPermissionId(userId, item.permissionType());
            UserPermission permission = repository.findById(id).orElseGet(() -> UserPermission.builder().id(id).build());
            permission.setStatus(item.status());
            permission.setUpdatedAt(now);
            repository.save(permission);
        }
        return get(userId);
    }
}
