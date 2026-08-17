package com.hq.backend.event;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventClassificationReviewRepository extends JpaRepository<EventClassificationReview, UUID> {

    Optional<EventClassificationReview> findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(UUID eventId);
}
