package com.hq.backend.event;

import com.hq.backend.event.dto.PendingEventReviewResponse;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface EventClassificationReviewRepository extends JpaRepository<EventClassificationReview, UUID> {

    Optional<EventClassificationReview> findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(UUID eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from EventClassificationReview r where r.reviewId = :reviewId and r.eventId = :eventId")
    Optional<EventClassificationReview> findByReviewIdAndEventIdForUpdate(UUID reviewId, UUID eventId);

    @Query("""
            select new com.hq.backend.event.dto.PendingEventReviewResponse(
                r.reviewId, e.eventId, e.startsAt, r.questionType,
                r.suggestedValue, r.classificationConfidence, r.askedAt)
            from EventClassificationReview r join Event e on e.eventId = r.eventId
            where e.userId = :userId and e.startsAt >= :from and e.startsAt < :to
              and r.answeredAt is null and e.locationState = 'undecided'
              and e.status = 'planned'
              and e.autoManageExcluded = false and e.meetingUrl is null
            order by e.startsAt, r.askedAt, r.reviewId
            """)
    List<PendingEventReviewResponse> findPendingReviews(UUID userId, Instant from, Instant to);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from EventClassificationReview r
            where r.eventId = :eventId and r.answeredAt is null
            order by r.askedAt, r.reviewId
            """)
    List<EventClassificationReview> findPendingByEventIdForUpdate(UUID eventId, Pageable pageable);

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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            WITH batch AS (
                SELECT review_id
                FROM event_classification_review
                WHERE title_snapshot IS NOT NULL AND asked_at <= :cutoff
                ORDER BY asked_at, review_id
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            )
            UPDATE event_classification_review r
            SET title_snapshot = NULL, title_purged_at = :purgedAt
            FROM batch
            WHERE r.review_id = batch.review_id AND r.title_snapshot IS NOT NULL
            """, nativeQuery = true)
    int purgeTitleSnapshots(@Param("cutoff") Instant cutoff,
                            @Param("purgedAt") Instant purgedAt,
                            @Param("batchSize") int batchSize);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            WITH batch AS (
                SELECT review_id
                FROM event_classification_review
                WHERE asked_at < :cutoff
                ORDER BY asked_at, review_id
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            )
            DELETE FROM event_classification_review r
            USING batch
            WHERE r.review_id = batch.review_id
            """, nativeQuery = true)
    int deleteExpiredReviews(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
