# Task 4 Report — Classification Contract, Input Normalization, and Policy Gate

## Scope delivered

- Added the `EventClassifier` port and immutable input/result contracts.
- Added an unconditional `NoOpEventClassifier`; Task 4 introduces no OpenAI client or conditional replacement bean.
- Added NFC title normalization that preserves output exactly apart from normalization and rejects null, blank, C0/C1 controls, unpaired surrogates, and more than 500 Unicode code points.
- Added validated `openai.classification` configuration with boot-safe defaults and disabled-by-default YAML values.
- Added `AiClassificationGate`: invalid/blank/mismatched configuration is `DISABLED`; rollout uses the unsigned first eight bytes of SHA-256 UUID UTF-8 input modulo 100; every evaluation reads the newest privacy consent anew.
- Added a deterministic latest privacy-consent repository query ordered by `recordedAt DESC, consentEventId DESC`.

## TDD evidence

The initial focused test execution failed at test compilation because the requested production contracts did not exist (`CalendarTitleNormalizer`, `AiClassificationGate`, and `AiClassificationProperties`). The minimal implementation was then added and the focused suite was run green.

The repository integration fixture initially exposed two fixture-only errors: PostgreSQL JDBC needs `Timestamp` rather than raw `Instant`, and `user_consent.user_id` requires an existing user. Both were corrected in the test fixture; no production recovery behavior was added.

## Verification

- `./gradlew test --tests '*CalendarTitleNormalizerTest' --tests '*AiClassificationGateTest' --tests '*AiClassificationPropertiesTest' --tests '*UserConsentRepositoryTest' --console=plain` — PASS
- `./gradlew test --tests '*BackendApplicationTests' --tests '*CalendarEventWriterTest' --tests '*CalendarSyncServiceTest' --console=plain` — PASS
- `./gradlew test --console=plain` — PASS
- `git diff --check` — PASS

## Privacy boundary

No title is logged or persisted by this task. The gate and normalizer do not emit title-bearing telemetry; the real external client remains intentionally deferred to Task 5.
