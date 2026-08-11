package com.hq.backend.place;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceVisitRepository extends JpaRepository<PlaceVisit, Long> {

    Optional<PlaceVisit> findFirstByUserIdAndPlaceIdAndExitedAtIsNullOrderByEnteredAtDesc(UUID userId, UUID placeId);
}
