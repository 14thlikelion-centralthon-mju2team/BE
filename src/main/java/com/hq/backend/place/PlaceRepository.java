package com.hq.backend.place;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceRepository extends JpaRepository<Place, UUID> {

    List<Place> findByUserIdAndArchivedAtIsNull(UUID userId);

    Optional<Place> findByIdAndUserId(UUID id, UUID userId);
}
