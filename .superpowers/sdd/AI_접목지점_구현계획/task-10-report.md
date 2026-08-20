# Task 10 Report — Golden set, integration regression, and release evidence

## Delivered

- Added a deterministic 200-row synthetic Korean JSONL golden set with strict five-field schema, 160 valid rows (online/offline 80 each), and 40 invalid rows covering blank, C0/C1 control, 501-code-point, and unpaired-surrogate input.
- Added a strict Responses-fixture golden contract: schema-valid response rate 100%, post-validation 160/160, and no provider calls for invalid input.
- Replaced the synthetic concatenating Google client in the PostgreSQL flow with the production `DefaultGoogleCalendarSyncClient` backed by two `MockRestServiceServer` HTTP pages. The regression asserts query fields, initial sync parameters, second-page `pageToken`, Authorization, and the final `nextSyncToken` CAS path.
- Hardened Task 7/8 with bounded real Event/review lock races for answer/purge and answer/delete, including Future 5-second bounds for purge/delete while locks are held, cancellation/shutdown cleanup, a bounded PATCH wait without direct DB polling, and a scheduler failure-log privacy canary.
- Added an `openai-eval` tagged Gradle task excluded from normal CI. It makes real classifier calls only after exact policy/dedicated-key/pricing approvals, pinned model, fixed 160-request authorization, conservative pre-network cost budget, and a production-equivalent 3-second connect/10-second read HTTP client. Every accepted live result must have the pinned resolved model and positive, internally consistent raw input/output/total usage in a package-private detailed evaluation result; missing usage fails closed before cost/F1 can pass. Operational meters retain the exact `input|output` direction contract and record no total tag. It then requires macro F1 >= 0.90, latency p95 <= 5 seconds, output-token p95 <= 50, and the approved cost cap.
- Added a provider-response privacy regression: an exact raw canary inside malformed Responses output traverses parser failure while canary/body text remains absent from captured logs, Event fields, and review fields.

## RED / GREEN

- RED: the golden contract initially failed because `/ai/event-online-golden-v1.jsonl` did not exist. After adding the synthetic resource, `EventClassificationGoldenSetTest` passed.
- RED: replacing the flow fake first failed because the test fixture tried to serialize Java-time DTOs with an unconfigured mapper; the fixture now serves raw Google API JSON, and the real client regression passes.
- GREEN: detailed provider usage is exposed only through a stateless package-private live-evaluation seam; the production meter contract remains exactly `input|output`. No Event user-field behavior changed.
- Final fresh-clean RED: `EventReviewConcurrencyTest` occasionally allowed the answer transaction to acquire the Event lock before PATCH because its ordering latch fired before the repository invocation. The deterministic fix signals only after PATCH has returned from `findOwnedForUpdate`, starts answer while PATCH owns the lock, and then releases PATCH; the focused concurrency suite passed after the change.
- Final branch review RED: an eligible-preserving user edit could receive a stale AI review, calendar time updates could race and overwrite user fields, arbitrary/non-HTTPS `openai.base-url` values could receive the key and title, and live evaluation omitted the documented input-token p95 limit. The fixes add a persisted Event optimistic revision carried from CREATED through review insertion, Event-first locking for existing calendar events, an exact approved HTTPS OpenAI endpoint policy enforced at bean and call gates, and input p95 <= 300 enforcement. Each finding was reproduced before its focused GREEN.
- The final transaction audit also found density pagination running under a read-only transaction. `getDensity` no longer opens a transaction across token refresh and Google HTTP pages; repository reads retain their own short boundaries.

## Verification

- Initial implementation SHA: `07970c3` (`test: complete AI release verification`); re-review hardening SHA: `122bc23` (`fix: harden AI release evaluation verification`); usage-seam fix SHA: `3cf2f40` (`fix: isolate live evaluation usage details`).
- Focused golden/integration/API/concurrency/retention suite: exit 0.
- `./gradlew --no-daemon clean test --console=plain`: exit 0, 260 tests, 0 failures, 0 errors, JDK 21/PostgreSQL 16.
- `./gradlew build --console=plain`: exit 0.
- Both Compose render commands: exit 0.
- `./gradlew openAiEvalTest --console=plain`: exit 0 with `OPENAI_EVAL_ENABLED` unset and zero provider requests.
- Python plan-engine verification used `uv run --python 3.13 --extra dev`: 474 tests collected/passed, Ruff reported `All checks passed!`, and mypy reported `Success: no issues found in 61 source files`. `git diff --name-only bc3609c -- ai/plan-engine` is empty.

## Honest release boundary

No live provider F1, latency p95, output-token p95, provider processing approval, or approved cost cap is claimed. The forced-tracked `doc/AI_접목지점_검증결과.md` contains the rollout and immediate rollback controls plus the required evidence placeholders.
