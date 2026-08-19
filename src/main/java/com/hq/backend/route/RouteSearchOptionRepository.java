package com.hq.backend.route;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RouteSearchOptionRepository extends JpaRepository<RouteSearchOption, UUID> {

    List<RouteSearchOption> findBySearchSessionIdAndUserIdOrderByRouteRankAsc(UUID searchSessionId, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select option from RouteSearchOption option
            where option.routeSearchOptionId = :routeOptionId and option.userId = :userId
            """)
    Optional<RouteSearchOption> findOwnedForUpdate(
            @Param("routeOptionId") UUID routeOptionId, @Param("userId") UUID userId);
}
