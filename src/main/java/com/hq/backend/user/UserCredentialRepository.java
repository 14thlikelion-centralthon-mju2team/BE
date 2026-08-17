package com.hq.backend.user;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCredentialRepository extends JpaRepository<UserCredential, UUID> {
}
