# Task 6 Report — Review-only event classification orchestration

## Implementation

- Added a `REQUIRES_NEW` `EventClassificationReviewWriter`. It obtains the Event's pessimistic lock before checking review eligibility, never changes the Event, and writes a title-free/purged review using the PostgreSQL partial-unique `ON CONFLICT ... DO NOTHING` insert.
- Added explicit create/attempt outcomes, a process-global nonblocking semaphore guard, and an orchestrator that applies the existing fresh consent/rollout gate and title normalizer before exactly one classifier invocation. Empty or unexpected classifier/review attempts are fail-closed and do not escape to block calendar sync.
- `CalendarSyncService` now retains only transient `(eventId, raw Google summary)` candidates for committed `CREATED` writes. Scheduled sync alone sends them to the orchestrator, tracks provider calls locally, calls it with `maxPerSync - providerCalls`, and advances the sync token only after all writes, recalculations, and classification attempts. Manual sync never invokes AI.

## TDD evidence

1. `EventClassificationReviewWriterTest` initially failed at test compilation because `EventClassificationReviewWriter` did not exist. It now proves eligibility, exact title-purge/provenance fields, native duplicate outcome, and the Event-first `TransactionTemplate` lock race where a concurrent user-side change makes the review stale.
2. `EventClassificationOrchestratorTest` initially failed at compilation because the orchestrator and concurrency guard did not exist. It now proves budget-first behavior, gate/invalid/busy skips, normalized input, one provider call, no ambient Spring transaction during the call, permit release, and writer outcome mapping.
3. `CalendarSyncServiceTest` initially failed at compilation because the classification dependencies were absent. It now proves scheduled `CREATED`-only handoff, manual no-AI behavior, local provider budget accounting, attempt-error token-CAS survival, and write/classification/token ordering.

## Verification

- `./gradlew test --tests '*EventClassificationReviewWriterTest' --tests '*EventClassificationOrchestratorTest' --tests '*CalendarSyncServiceTest' --console=plain` — PASS.
- `git diff --check` — PASS.
- `./gradlew test --console=plain` — **1 unrelated failure out of 214**: `WellnessEventGateTest.stop_today_응답은_DB제약을_통과하고_동일_action의_미발송_schedule을_취소한다` at line 143 (`reloadedFuture.cancelledAt` is null). It reproduces when run alone, and no wellness source/test files are in this Task 6 diff.

## Scope note

The current `EventService.update()` does not yet use a pessimistic Event lock; that endpoint synchronization is explicitly Task 7 scope. The Task 6 PostgreSQL race test models the required Event-first writer interaction through `TransactionTemplate` and `findByIdForUpdate`, without claiming that the Task 7 PATCH race is already solved.
