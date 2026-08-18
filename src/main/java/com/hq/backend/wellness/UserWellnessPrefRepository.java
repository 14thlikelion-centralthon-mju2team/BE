package com.hq.backend.wellness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserWellnessPrefRepository extends JpaRepository<UserWellnessPref, UserWellnessPrefId> {

    List<UserWellnessPref> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);

    Optional<UserWellnessPref> findByUserIdAndWellnessTopic(UUID userId, String wellnessTopic);

    List<UserWellnessPref> findByUserIdAndIsEnabledTrue(UUID userId);
}
