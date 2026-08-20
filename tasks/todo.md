# AI 일정 분류 리뷰 구현

## Constraints

- review-only: AI 결과로 Event 사용자 필드를 직접 변경하지 않는다.
- scheduled sync only: `syncForUser()`에는 AI를 연결하지 않는다.
- privacy-policy exact-match와 기본 no-op/fail-closed를 유지한다.
- Event와 review의 잠금 순서는 항상 Event → review다.
- 테스트를 먼저 실패시키고 기대한 Red임을 확인한 뒤 production code를 작성한다.
- Homebrew JDK 21 경로와 CI 보안 환경변수를 명시하고 PostgreSQL 16 컨테이너를 사용해 각 Task를 검증한다.

## Checklist

- [x] Task 0: 실행 기준선과 작업 추적 생성
- [x] Task 1: 리뷰 무결성과 provenance 스키마 고정
  - [x] Write repository integration tests for pending-review uniqueness and provenance round-trip
  - [x] Capture expected RED against the pre-V27 schema
  - [x] Add V27 schema safety migration and JPA/repository contracts
  - [x] Run focused and EventController regressions; self-review and commit
- [x] Task 2: Google 조회 DTO와 다페이지 수집 분리
- [x] Task 3: 캘린더 쓰기 트랜잭션과 sync token CAS
  - [x] Add RED tests for Event writer source/event concurrency and token CAS
  - [x] Add REQUIRES_NEW event/state writers and conflict facade
  - [x] Add RED orchestration tests for mutex, 410 recovery, token ordering, manual no-AI
  - [x] Refactor sync orchestration outside transactions and verify focused/PostgreSQL/full regressions
  - [x] Self-review, diff check, report, and commit
  - [x] Review fix: accept id-only cancellation tombstones
  - [x] Review fix: normalize only external-event unique insert conflicts
  - [x] Review fix: exercise manual false classification gate after CREATED upsert
- [x] Task 4: 분류 계약, 입력 정규화, 동의·롤아웃 게이트
  - [x] Write focused RED tests for title normalization, policy gate, configuration binding, and NoOp selection
  - [x] Add minimal fail-closed classification contracts, properties, normalizer, and consent/rollout gate
  - [x] Verify focused tests, application context, full regression, diff; write report and commit
- [x] Task 5: OpenAI Responses API strict client
  - [x] Add strict Responses API DTOs, a dedicated bounded RestClient, and a conditional `@Primary` classifier
  - [x] Verify exact request shape, adversarial input escaping, fail-closed parsing, no retry, no-sensitive-data logs, and configuration fallback
  - [x] Run focused/context/full regressions, independent review, diff check, report, and commit
- [ ] Task 6: 신규 일정 분류와 review 저장 orchestration
- [ ] Task 7: pending 조회와 stale-safe 답변 API
- [ ] Task 8: 24시간 방어 purge와 90일 review 삭제
- [ ] Task 9: 저카디널리티 관측성과 배포 설정
- [ ] Task 10: 골든셋, 통합 회귀, 출시 증거

## Review

- Draft PR: https://github.com/14thlikelion-centralthon-mju2team/BE/pull/204
- Self-review: https://github.com/14thlikelion-centralthon-mju2team/BE/pull/204#issuecomment-5355635832
- Baseline: JDK 21 + PostgreSQL 16에서 `./gradlew test` 138 tests, 0 failures.
- Task 1: V27 partial unique index and provenance constraints, immutable entity mapping, locking APIs, and PostgreSQL repository tests verified before commit.
- Task 1 independent review: APPROVED (Critical 0, Important 0). Minor follow-up is covered by the later end-to-end creation/update integration tests.
- Task 2 independent review: two pagination findings fixed with TDD; scoped re-review APPROVED (Critical 0, Important 0).
- Task 3: Event/source writes and sync-token CAS now use isolated `REQUIRES_NEW` writers; focused PostgreSQL tests and the full Gradle suite passed before commit.
- Task 3 independent review: two data-loss findings fixed with TDD; scoped re-review APPROVED (Critical 0, Important 0).
- Task 4: Added a fail-closed title normalizer, exact privacy-consent gate, stable rollout, validated configuration, and unconditional NoOp classifier. Focused PostgreSQL/context tests and the full Gradle suite passed before commit.
- Task 4 independent review: APPROVED (Critical 0, Important 0, Minor 0).
- Task 5: Added a strict, fail-closed OpenAI Responses client that sends only the normalized calendar-title JSON; focused contract tests, context validation, and full suite passed before commit.
- Task 5 independent review: APPROVED (Critical 0, Important 0). Added direct regressions for both enum domains and confidence bounds after review.
- Task 5 root follow-up: DTO-scoped null omission fixes the exact JSON Schema payload; actual timeout-path log capture proves exception and sensitive sentinels remain absent.
