# Task 8 Report — AI review retention

## Implementation

- Added PostgreSQL-native, stable `(asked_at, review_id)` CTE mutations. Title snapshots are purged only when `asked_at <= cutoff` and still non-null; the mutation only writes `title_snapshot` and `title_purged_at`. Expired review deletion uses `asked_at < cutoff` regardless of whether the review is pending or answered.
- Added a separate `EventClassificationReviewRetentionBatchWriter` bean. Each native mutation runs in its own `REQUIRES_NEW` transaction, so the service never self-invokes a transaction proxy and no transaction spans a complete retention pass.
- Added a mutation-driven retention service that drains successive 500-row batches until the next batch is short. A skipped locked batch ends this pass and remains eligible for the next scheduled pass.
- Added an injected UTC `Clock` and a scheduler that purges once on `ApplicationReadyEvent`, every configurable fixed delay (default five minutes), and deletes at a configurable UTC cron (default 03:30). Scheduler failures log no row identifiers, titles, or exception payload and are retried by the next invocation.

## TDD evidence

1. `EventClassificationReviewRetentionServiceTest` and `EventClassificationReviewRetentionSchedulerTest` initially failed to compile because the retention service and batch writer did not exist. This is the expected RED for the absent Task 8 contract.
2. After the native CTE repository methods, isolated writer, drain service, and scheduler were added, the focused suite passed. The PostgreSQL tests prove the 24-hour inclusive purge boundary, 90-day exclusive deletion boundary, pending/answered deletion, `review_id` tie breaking, 501-row draining through 500-row batches, idempotent follow-up behavior, answer/purge safety, and delete `SKIP LOCKED` retry behavior.
3. A scheduler failure test was added with the retry behavior removed temporarily; it failed because the exception escaped. Restoring the non-sensitive failure boundary made the test pass and demonstrates that a later scheduler invocation retries the work.

## Verification

All commands use JDK 21, local PostgreSQL 16, and CI-safe security environment values.

- `./gradlew test --tests '*EventClassificationReviewRetention*' --console=plain` — PASS.
- `./gradlew test --tests '*EventClassificationReviewRetention*' --tests '*EventControllerTest' --tests '*EventReviewConcurrencyTest' --tests '*EventClassificationReviewWriterTest' --tests '*EventClassificationOrchestratorTest' --tests '*CalendarSyncServiceTest' --console=plain` — PASS.
- `./gradlew test --console=plain` — PASS (`BUILD SUCCESSFUL in 18s`).
- `git diff --check` — PASS.

## Scope

- No distributed lock was introduced. `FOR UPDATE SKIP LOCKED` makes simultaneous local workers safe at row/batch scope, and a temporarily locked row is retried by a later scheduled pass.
- Retention metrics are intentionally deferred to Task 9. No IDs, title snapshots, or sensitive values are emitted by the new retention code.
