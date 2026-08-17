package com.hq.backend.wellness;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserWellnessPrefRepository extends JpaRepository<UserWellnessPref, UserWellnessPrefId> {

    void deleteByUserId(UUID userId);
}
