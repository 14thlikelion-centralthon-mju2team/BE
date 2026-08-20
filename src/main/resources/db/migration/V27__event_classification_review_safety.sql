DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM event_classification_review
        WHERE answered_at IS NULL
        GROUP BY event_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'duplicate pending event classification reviews exist';
    END IF;
END $$;

ALTER TABLE event_classification_review
    ADD COLUMN provider text,
    ADD COLUMN classifier_version text,
    ADD COLUMN prompt_version text,
    ADD COLUMN schema_version text;

ALTER TABLE event_classification_review
    ADD CONSTRAINT ck_classification_review_provider
        CHECK (provider IS NULL OR provider = 'openai'),
    ADD CONSTRAINT ck_classification_review_provenance
        CHECK (provider IS NULL OR (
            model_version IS NOT NULL
            AND classifier_version IS NOT NULL
            AND prompt_version IS NOT NULL
            AND schema_version IS NOT NULL
        ));

CREATE UNIQUE INDEX uq_classification_review_pending_event
    ON event_classification_review(event_id)
    WHERE answered_at IS NULL;

CREATE INDEX ix_classification_review_title_purge
    ON event_classification_review(asked_at, review_id)
    WHERE title_snapshot IS NOT NULL;

CREATE INDEX ix_classification_review_delete
    ON event_classification_review(asked_at, review_id);
