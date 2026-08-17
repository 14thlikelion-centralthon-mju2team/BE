package com.hq.backend.preprule;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPrepRuleRepository extends JpaRepository<UserPrepRule, UUID> {
}
