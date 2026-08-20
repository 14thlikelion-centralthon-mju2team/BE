# Task 7 Report — Pending event reviews and stale-safe answers

## Implementation

- Added a one-query, ownership-scoped pending-review projection at `GET /events/reviews/pending`. It accepts an Instant `[from, to)` range of at most 31 days, orders by `startsAt`, `askedAt`, `reviewId`, and returns only review metadata safe for the client (no title, model, or provenance fields).
- Answer requests now bind to an exact `reviewId` and Event. The transaction acquires the owned Event lock first, then the exact review lock; foreign/mismatched IDs are hidden as `REVIEW_NOT_FOUND`, closed reviews are `REVIEW_ALREADY_CLOSED`, and no-longer-eligible Events are `REVIEW_STALE`.
- Confirmed online/offline answers are user-confirmed Event changes: online resolves to `not_required` with no meeting URL, offline resolves to `required_missing`; both close the review with the normalized user answer in the same transaction.
- `PATCH /events/{eventId}` now acquires the owned Event lock and closes a pending review under that Event lock when its final state is no longer eligible. This closure purges the title and retains `userAnswer = null`. `DELETE` likewise locks the Event but intentionally leaves pending reviews open so a later answer is stale rather than auto-closed.

## TDD evidence

1. Expanded `EventControllerTest` before implementation. The focused RED run failed for the missing pending endpoint/range validation/PATCH closure contracts. A separate concurrency RED failed compilation because `EventReviewRequest` did not yet accept `reviewId`.
2. Added Event-first answer/PATCH locking and the projection, then verified focused controller plus PostgreSQL double-answer and PATCH-first races.
3. Independent review found stale-projection, request-422, and delete/answer serialization gaps. Added controller RED coverage: the first two failures reproduced; then extended the projection with the full Task 6 eligibility predicate, made review request validation return `422 VALIDATION_ERROR`, and locked deletion.

## Verification

- `./gradlew test --tests '*EventControllerTest' --tests '*EventReviewConcurrencyTest' --tests '*EventClassificationReviewWriterTest' --tests '*EventClassificationOrchestratorTest' --tests '*CalendarSyncServiceTest' --console=plain` — PASS.
- `./gradlew test --tests '*EventControllerTest' --tests '*EventReviewConcurrencyTest' --console=plain` after review fixes — PASS.
- `./gradlew test --console=plain` — PASS (`BUILD SUCCESSFUL in 18s`).
- `git diff --check` — PASS.

## Review

- Initial independent review reported three Important findings: missing stale eligibility filters in the projection, review-request validation status mismatch, and an unlocked delete/answer race.
- All three were corrected with focused regression coverage. The unresponsive scoped reviewer was stopped; root scheduled a fresh post-commit independent review.
