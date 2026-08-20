# Task 10 Report — Golden set, integration regression, and release evidence

## Delivered

- Added a deterministic 200-row synthetic Korean JSONL golden set with strict five-field schema, 160 valid rows (online/offline 80 each), and 40 invalid rows covering blank, C0/C1 control, 501-code-point, and unpaired-surrogate input.
- Added a strict Responses-fixture golden contract: schema-valid response rate 100%, post-validation 160/160, and no provider calls for invalid input.
- Replaced the synthetic concatenating Google client in the PostgreSQL flow with the production `DefaultGoogleCalendarSyncClient` backed by two `MockRestServiceServer` HTTP pages. The regression asserts query fields, initial sync parameters, second-page `pageToken`, Authorization, and the final `nextSyncToken` CAS path.
- Hardened Task 7/8 with bounded real Event/review lock races for answer/purge and answer/delete, including Future 5-second bounds for purge/delete while locks are held, cancellation/shutdown cleanup, a bounded PATCH wait without direct DB polling, and a scheduler failure-log privacy canary.
- Added an `openai-eval` tagged Gradle task excluded from normal CI. It makes real classifier calls only after exact policy/dedicated-key/pricing approvals, pinned model, fixed 160-request authorization, conservative pre-network cost budget, and a production-equivalent 3-second connect/10-second read HTTP client. Every accepted live result must have the pinned resolved model and positive, internally consistent input/output/total usage deltas; missing usage fails closed before cost/F1 can pass. It then requires macro F1 >= 0.90, latency p95 <= 5 seconds, output-token p95 <= 50, and the approved cost cap.
- Added a provider-response privacy regression: an exact raw canary inside malformed Responses output traverses parser failure while canary/body text remains absent from captured logs, Event fields, and review fields.

## RED / GREEN

- RED: the golden contract initially failed because `/ai/event-online-golden-v1.jsonl` did not exist. After adding the synthetic resource, `EventClassificationGoldenSetTest` passed.
- RED: replacing the flow fake first failed because the test fixture tried to serialize Java-time DTOs with an unconfigured mapper; the fixture now serves raw Google API JSON, and the real client regression passes.
- GREEN: the release verifier is test-scope except for the low-cardinality `total` token metric needed to prove provider usage accounting. No Event user-field behavior changed.

## Verification

- Initial implementation SHA: `07970c3` (`test: complete AI release verification`); re-review fix SHA: `122bc23` (`fix: harden AI release evaluation verification`).
- Focused golden/integration/API/concurrency/retention suite: exit 0.
- `./gradlew --no-daemon clean test --console=plain`: exit 0, 260 tests, 0 failures, 0 errors, JDK 21/PostgreSQL 16.
- `./gradlew build --console=plain`: exit 0.
- Both Compose render commands: exit 0.
- `./gradlew openAiEvalTest --console=plain`: exit 0 with `OPENAI_EVAL_ENABLED` unset and zero provider requests.
- Python plan-engine verification used `uv run --python 3.13 --extra dev`: 474 tests collected/passed, Ruff reported `All checks passed!`, and mypy reported `Success: no issues found in 61 source files`. `git diff --name-only bc3609c -- ai/plan-engine` is empty.

## Honest release boundary

No live provider F1, latency p95, output-token p95, provider processing approval, or approved cost cap is claimed. The forced-tracked `doc/AI_접목지점_검증결과.md` contains the rollout and immediate rollback controls plus the required evidence placeholders.
