# Task 3 Report: 캘린더 쓰기 트랜잭션과 sync token CAS

## Implementation

- Added `CalendarEventWriter` plus `CalendarEventTransactionWriter` and moved default-source/Event changes into `REQUIRES_NEW` write transactions. New events never persist Google `summary` into any Event field.
- Default source creation uses PostgreSQL `INSERT ... ON CONFLICT DO NOTHING`, covering the CalendarService connect/sync race without a rollback-only constraint path.
- Unique `(calendar_source_id, external_event_id)` conflicts leave the failed transaction before the facade re-reads in a new transaction. Only a confirmed pre-existing Event is normalized to `UNCHANGED`; unrelated database violations are rethrown.
- Added exact initial/replace/clear sync-token CAS repository operations behind `CalendarSyncStateWriter` `REQUIRES_NEW` methods.
- Removed `@Transactional` from `syncForUser()`. Both scheduled and manual orchestrations lock by connection ID, re-read the connection after acquiring the JVM mutex, and make refresh/Google calls outside a surrounding application transaction. Scheduled uses `classificationAllowed=true`; manual passes `false` for Task 6's future classifier hook.
- A 410 clears the expected old token by CAS and performs exactly one full-sync retry. A stale clear, second 410, missing final token, or Event writer failure stops before token advance. Recalculation is only triggered after a committed `UPDATED` writer result with `requiresPlanRecompute=true`.

## RED

```text
./gradlew test --tests '*CalendarEventWriterTest' --tests '*CalendarSyncServiceTest'
compileTestJava FAILED
cannot find symbol: class CalendarEventWriter
cannot find symbol: class CalendarSyncStateWriter
```

The tests referenced the prescribed new write/state boundaries before those production types existed, establishing the intended RED condition.

## GREEN and regression

Focused PostgreSQL execution:

```text
./gradlew test --tests '*CalendarEventWriterTest' --tests '*CalendarSyncServiceTest' --console=plain
BUILD SUCCESSFUL in 6s
```

Coverage includes create/unchanged/time-update/cancel/all-day skip, source/Event concurrent insert normalization, token CAS stale cases, mutex skip, 410 recovery/stale clear/second 410 stop, writer failure token suppression, and manual external I/O without an active transaction.

Full regression:

```text
./gradlew test --console=plain
BUILD SUCCESSFUL in 18s
```

Both commands used JDK 21, PostgreSQL 16, and the CI security environment variables.

## Self-review

- `Optional.empty()` is used only for missing id/all-day and missing cancelled Event normal skips; database failures are not converted to empty.
- Event creation does not assign `summary`, `displayLabel`, or other user fields.
- Token advance happens only after the complete Event loop, recomputation attempts, and the no-op Task 6 hook; a null final token does not advance.
- `git diff --check` passed before staging.

## Concerns

- Task 6 has not introduced the classifier/review orchestration dependency yet. This task preserves its scheduled/manual gate at the package-private orchestration boundary but intentionally performs no AI call.

## Independent review fixes

### RED

After the independent review, the focused suite failed with the expected two behavioral reproductions:

- an id-only `cancelled` Google tombstone returned `Optional.empty()` before the transaction writer and therefore did not cancel the existing Event;
- an existing Event update with `endsAt < startsAt` violated `ck_event_time_order`, but the broad facade catch re-read that Event and returned `UNCHANGED`.

The initial manual-gate observability test also exposed that the hook was private. It was made package-private solely as the focused orchestration seam, then the runtime reproductions above were executed.

### GREEN

- `CalendarEventWriter` now accepts any id-bearing cancellation before timed-event validation. Missing cancelled Events still return the permitted normal skip.
- `CalendarEventTransactionWriter` wraps only the new Event insert's PostgreSQL `23505` / `uq_event_external` conflict. The facade alone normalizes that marker after its separate re-read; update constraints and all other database errors propagate.
- The manual sync test now completes a CREATED upsert with a non-null final token, verifies `processCreatedCandidates(..., false)`, and verifies token advance. The no-op hook remains intentionally empty until Task 6 supplies the classifier dependency.

```text
./gradlew test --tests '*CalendarEventWriterTest' --tests '*CalendarSyncServiceTest' --console=plain
BUILD SUCCESSFUL in 5s
```
