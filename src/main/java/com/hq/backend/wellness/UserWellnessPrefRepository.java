package com.hq.backend.wellness;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserWellnessPrefRepository extends JpaRepository<UserWellnessPref, UserWellnessPrefId> {

    List<UserWellnessPref> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
