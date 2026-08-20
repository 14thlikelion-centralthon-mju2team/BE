package com.hq.backend.event;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventClassificationReviewRepository extends JpaRepository<EventClassificationReview, UUID> {

    Optional<EventClassificationReview> findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(UUID eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from EventClassificationReview r where r.reviewId = :reviewId and r.eventId = :eventId")
    Optional<EventClassificationReview> findByReviewIdAndEventIdForUpdate(UUID reviewId, UUID eventId);

    boolean existsByEventIdAndAnsweredAtIsNull(UUID eventId);

    @Modifying
    @Query(value = """
            INSERT INTO event_classification_review (
                review_id, event_id, title_snapshot, question_type, suggested_value,
                user_answer, model_version, classification_confidence, asked_at,
                answered_at, title_purged_at, provider, classifier_version,
                prompt_version, schema_version
            ) VALUES (
                :reviewId, :eventId, NULL, :questionType, :suggestedValue,
                NULL, :modelVersion, :confidence, :askedAt,
                NULL, :askedAt, :provider, :classifierVersion,
                :promptVersion, :schemaVersion
            ) ON CONFLICT (event_id) WHERE answered_at IS NULL DO NOTHING
            """, nativeQuery = true)
    int insertPendingIfAbsent(
            @Param("reviewId") UUID reviewId,
            @Param("eventId") UUID eventId,
            @Param("questionType") String questionType,
            @Param("suggestedValue") String suggestedValue,
            @Param("modelVersion") String modelVersion,
            @Param("confidence") BigDecimal confidence,
            @Param("askedAt") Instant askedAt,
            @Param("provider") String provider,
            @Param("classifierVersion") String classifierVersion,
            @Param("promptVersion") String promptVersion,
            @Param("schemaVersion") String schemaVersion);
}
