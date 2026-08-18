package com.hq.backend.personalization;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPrepEstimateRepository extends JpaRepository<UserPrepEstimate, UUID> {

    List<UserPrepEstimate> findByUserIdAndValidToIsNull(UUID userId);

    // 되돌리기(§15.3) — 최신순으로 현재 값과 직전 값을 함께 가져온다.
    List<UserPrepEstimate> findByUserIdAndScopeTypeOrderByValidFromDesc(UUID userId, String scopeType);
}
