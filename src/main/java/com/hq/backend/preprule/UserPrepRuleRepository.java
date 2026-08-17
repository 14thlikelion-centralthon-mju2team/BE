package com.hq.backend.preprule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPrepRuleRepository extends JpaRepository<UserPrepRule, UUID> {

    List<UserPrepRule> findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(UUID userId);

    Optional<UserPrepRule> findByPrepRuleIdAndUserId(UUID prepRuleId, UUID userId);
}
