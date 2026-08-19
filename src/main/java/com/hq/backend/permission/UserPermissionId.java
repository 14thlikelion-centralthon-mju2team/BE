package com.hq.backend.permission;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UserPermissionId implements Serializable {
    private UUID userId;
    private String permissionType;
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof UserPermissionId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(permissionType, that.permissionType);
    }
    @Override public int hashCode() { return Objects.hash(userId, permissionType); }
}
