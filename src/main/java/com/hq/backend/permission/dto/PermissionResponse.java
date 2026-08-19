package com.hq.backend.permission.dto;

import com.hq.backend.permission.UserPermission;

public record PermissionResponse(String permissionType, String status) {
    public static PermissionResponse from(UserPermission permission) {
        return new PermissionResponse(permission.getId().getPermissionType(), permission.getStatus());
    }
}
