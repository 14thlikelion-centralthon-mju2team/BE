package com.hq.backend.permission;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_permission")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPermission {
    @EmbeddedId
    private UserPermissionId id;
    @Setter @Column(nullable = false)
    private String status;
    @Setter @Column(nullable = false)
    private Instant updatedAt;
}
