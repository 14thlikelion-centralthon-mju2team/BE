package com.hq.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String providerUid;

    @Column(unique = true, nullable = false)
    private String email;

    private String passwordHash;

    @Column(nullable = false)
    private String nickname;

    @Column(nullable = false)
    private String timezone;
    private Instant ageConfirmedAt;
    private Instant archivedAt;

    @Column(nullable = false)
    private Instant createdAt;
}
