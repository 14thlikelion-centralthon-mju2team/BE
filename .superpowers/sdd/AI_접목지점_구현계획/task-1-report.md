# Task 1 Report: 리뷰 무결성과 provenance 스키마 고정

## Implementation

- Added `V27__event_classification_review_safety.sql`. It blocks migration when historical duplicate pending reviews exist, adds the four missing provenance columns, restricts the provider to `openai`, requires complete provenance when a provider is present, and creates the pending-review partial unique index plus title-purge and deletion indexes.
- Added immutable (`updatable = false`, no setter) provenance mappings for `provider`, `modelVersion`, `classifierVersion`, `promptVersion`, and `schemaVersion`.
- Added the requested pessimistic Event and review lookup methods and pending-review existence lookup.
- Added a real PostgreSQL `@SpringBootTest` that verifies one unanswered review per Event and persistence-to-JPA provenance round-trip. Its new-review fixture keeps `titleSnapshot` null and records `titlePurgedAt`.

## Files

- `src/main/resources/db/migration/V27__event_classification_review_safety.sql`
- `src/main/java/com/hq/backend/event/EventClassificationReview.java`
- `src/main/java/com/hq/backend/event/EventClassificationReviewRepository.java`
- `src/main/java/com/hq/backend/event/EventRepository.java`
- `src/test/java/com/hq/backend/event/EventClassificationReviewRepositoryTest.java`
- `tasks/todo.md`

## RED

Command:

```bash
JWT_SECRET=ci-test-jwt-secret-that-is-at-least-64-bytes-long-for-hmac-sha512-algorithm!! ENCRYPTION_SECRET=ci-test-encryption-secret-at-least-32-bytes ENCRYPTION_SALT=deadbeefdeadbeefdeadbeefdeadbeef EMAIL_VERIFICATION_ENABLED=false JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH ./gradlew test --tests '*EventClassificationReviewRepositoryTest' --console=plain
```

Output: `2 tests completed, 2 failed`.

- `provenance은_저장후_조회에도_보존된다`: `BadSqlGrammarException` because `provider` did not yet exist.
- `event당_미응답_review은_하나만_저장된다`: assertion failed because the second pending review was accepted without the partial unique index.

Reason: both failures directly demonstrate the absent V27 schema contracts. A preliminary fixture run first revealed an `event_user_id_fkey` failure; the fixture was corrected to persist a real User before recording the required RED.

## GREEN

The same focused command completed successfully after V27 and the mappings were added:

```text
BUILD SUCCESSFUL in 5s
4 actionable tasks: 2 executed, 2 up-to-date
```

Flyway log confirms migration to `version "27 - event classification review safety"`.

## Regression

Command:

```bash
JWT_SECRET=ci-test-jwt-secret-that-is-at-least-64-bytes-long-for-hmac-sha512-algorithm!! ENCRYPTION_SECRET=ci-test-encryption-secret-at-least-32-bytes ENCRYPTION_SALT=deadbeefdeadbeefdeadbeefdeadbeef EMAIL_VERIFICATION_ENABLED=false JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH ./gradlew test --tests '*EventControllerTest' --console=plain
```

Output:

```text
BUILD SUCCESSFUL in 6s
4 actionable tasks: 1 executed, 3 up-to-date
```

## Self-review

- Verified all prescribed SQL statements and constraint names are present verbatim.
- Confirmed review provenance cannot be updated through JPA and has no setter.
- Confirmed the Event is never mutated by this task and repository lock order is enabled for future Event → review transactions.
- Confirmed the new review test does not store a title snapshot.
- Ran `git diff --check` before staging; reviewer found no Critical or Important issue.

## Concerns

- Migration deliberately aborts if existing pending-review duplicates are found; it does not auto-delete or alter historical data, so production deployment requires manual resolution if the exception occurs.
- The focused and adjacent regression suites passed against local PostgreSQL 16. The full suite was not rerun because Task 0 already captured the 138-test baseline and this task's explicit regression command is `EventControllerTest`.
