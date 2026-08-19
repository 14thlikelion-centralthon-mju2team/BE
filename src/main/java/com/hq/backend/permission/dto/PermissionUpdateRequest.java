package com.hq.backend.permission.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record PermissionUpdateRequest(@NotEmpty List<@Valid PermissionItem> permissions) {
    public record PermissionItem(
            @NotBlank @Pattern(regexp = "calendar|location|notification|background_location") String permissionType,
            @NotBlank @Pattern(regexp = "granted|denied|restricted|not_determined") String status) {
    }
}
