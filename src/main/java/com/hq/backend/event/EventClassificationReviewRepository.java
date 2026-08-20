package com.hq.backend.event;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventClassificationReviewRepository extends JpaRepository<EventClassificationReview, UUID> {

    Optional<EventClassificationReview> findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(UUID eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from EventClassificationReview r where r.reviewId = :reviewId and r.eventId = :eventId")
    Optional<EventClassificationReview> findByReviewIdAndEventIdForUpdate(UUID reviewId, UUID eventId);

    boolean existsByEventIdAndAnsweredAtIsNull(UUID eventId);
}
