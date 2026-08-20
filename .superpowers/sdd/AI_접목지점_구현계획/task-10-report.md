# Task 10 Report — Golden set, integration regression, and release evidence

## Delivered

- Added a deterministic 200-row synthetic Korean JSONL golden set with strict five-field schema, 160 valid rows (online/offline 80 each), and 40 invalid rows covering blank, C0/C1 control, 501-code-point, and unpaired-surrogate input.
- Added a strict Responses-fixture golden contract: schema-valid response rate 100%, post-validation 160/160, and no provider calls for invalid input.
- Added PostgreSQL integration coverage for two-page fixture sync through committed Event, title-free pending review, and real user answer. It also covers consent/revoke suppression, timeout/malformed fail-closed behavior, duplicate sync, PATCH closure, and canary leakage across logs/Event/review persistence.
- Hardened Task 7/8 test waits to five seconds, added pending ordering review-id tie coverage, and added exact-500 retention drain coverage.
- Added an `openai-eval` tagged Gradle task excluded from normal CI. It makes no live request by default; enabling it without policy approval, key, or cost cap fails before provider I/O.

## RED / GREEN

- RED: the golden contract initially failed because `/ai/event-online-golden-v1.jsonl` did not exist. After adding the synthetic resource, `EventClassificationGoldenSetTest` passed.
- Integration and hardening tests exercise existing Tasks 1–9 behavior, so no Task 10 production behavior was added. Their first focused execution exposed test-fixture issues (sequential mock expectations and null-unsafe test lists); the test fixtures were corrected without changing production code.

## Verification

- Focused golden/integration/API/concurrency/retention suite: exit 0.
- `./gradlew clean test --console=plain`: exit 0, 257 tests, 0 failures, JDK 21/PostgreSQL 16.
- `./gradlew build --console=plain`: exit 0.
- Both Compose render commands: exit 0.
- Python plan-engine verification is blocked before pytest: current `uv` selects CPython 3.14, while `pydantic-core 2.33.2` / PyO3 supports through Python 3.13. `git diff --name-only bc3609c -- ai/plan-engine` is empty.

## Honest release boundary

No live provider F1, latency p95, output-token p95, provider processing approval, or approved cost cap is claimed. The forced-tracked `doc/AI_접목지점_검증결과.md` contains the rollout and immediate rollback controls plus the required evidence placeholders.
