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
- [ ] Task 2: Google 조회 DTO와 다페이지 수집 분리 (in progress)
- [ ] Task 3: 캘린더 쓰기 트랜잭션과 sync token CAS
- [ ] Task 4: 분류 계약, 입력 정규화, 동의·롤아웃 게이트
- [ ] Task 5: OpenAI Responses API strict client
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
