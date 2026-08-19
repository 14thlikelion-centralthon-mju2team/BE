-- Preserve the original V16 checksum after it was applied in the integration test DB.
-- Raw/legacy Event inserts also need the same updated_at default as JPA's @PrePersist path.
alter table event
    alter column updated_at set default now();
