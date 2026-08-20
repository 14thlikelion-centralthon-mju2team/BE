# AI 접목지점 출시 검증 결과

## 검증 범위와 실행 환경

- 대상 커밋: Task 10 커밋 후 SHA를 아래 증적 표에 기록한다.
- Java: Homebrew OpenJDK 21, 데이터베이스: PostgreSQL 16.
- 골든셋: `src/test/resources/ai/event-online-golden-v1.jsonl`의 원문 재배포 위험이 없는 합성 한국어 200건이다. 필드는 `id`, `title`, `expected`, `validInput`, `category`만 사용한다. 유효 160건은 online/offline 각 80건이고, 무효 40건은 blank/control/501 code point/unpaired surrogate 각 10건이다.
- 기본 CI는 OpenAI 네트워크 호출을 하지 않는다. `openAiEvalTest`는 `openai-eval` 태그를 명시적으로 선택할 때만 실행되며, `OPENAI_EVAL_ENABLED=true`인 경우 정책 승인, 전용 키, 예산 cap이 없으면 provider 호출 전 실패한다.

## 오프라인 계약 증거

- 골든셋 계약은 strict provider fixture 160건으로 schema-valid rate 100%, 서버 후검증 160/160, invalid input provider call 0건을 검증한다.
- 통합 검증은 두 페이지 Google fixture → committed Event → strict Responses fixture 분류 → title 없는 pending review → 사용자 answer를 실제 PostgreSQL writer/lock/gate로 실행한다.
- canary `TASK10_PRIVACY_CANARY_9f1f0c`는 provider response와 예외 fixture에 주입한다. appender 출력, Event의 사용자 텍스트 필드, review의 저장 필드에 남지 않는지 검증한다.
- 미동의, revoke, timeout, malformed response, duplicate sync, 사용자 PATCH 후 답변은 Event를 AI가 자동으로 `undecided` 밖으로 바꾸지 못함을 검증한다.

## 라이브 평가 상태

라이브 OpenAI 평가는 이 커밋에서 실행하지 않았다. 공급자 처리 조건 승인, 전용 평가 키, 예산 cap의 운영 승인 증적이 제공되지 않았기 때문이다. 따라서 아래 지표를 **통과했다고 주장하지 않는다**.

- macro F1 >= 0.90
- provider latency p95 <= 5초
- output token p95 <= 50
- 실제 비용 cap 준수

승인 후에만 `OPENAI_EVAL_ENABLED=true OPENAI_EVAL_POLICY_APPROVED=true OPENAI_API_KEY=... OPENAI_EVAL_MAX_COST_USD=... ./gradlew openAiEvalTest`로 별도 실행하고, provider 출력 요약·지연·토큰·비용을 이 문서에 기록한다. 기본 `./gradlew test`에는 포함되지 않는다.

## 단계적 rollout 및 rollback runbook

1. 5% rollout은 최소 7일 **및** 사용자 답변 100건이 모두 충족된 뒤 agreement >= 80%, provider failure < 5%, p95 < 5초일 때만 25%로 올린다.
2. 25%도 동일한 기간·답변 수·agreement·failure·p95 기준을 다시 충족한 뒤에만 100%를 결정한다.
3. privacy leak, user overwrite, duplicate review가 한 건이라도 발생하거나 provider failure >= 10% 또는 p95 >= 10초면 즉시 `OPENAI_CLASSIFICATION_ENABLED=false`로 되돌린다. 배포 환경을 재기동하고 private metrics scrape에서 rollback 이후 신규 provider call이 0인지 확인한다.
4. 운영 담당자는 privacy-policy version, provider data-processing approval/retention 조건, pinned model, `OPENAI_EVAL_MAX_COST_USD` 예산 cap을 배포 승인 기록에 연결한다. 이 저장소에는 해당 외부 승인이나 비용 cap 값이 없다.

## 최종 명령 증적

| 명령 | 상태 | 비고 |
| --- | --- | --- |
| `JAVA_HOME=...openjdk@21... ./gradlew clean test --console=plain` | exit 0 | 257 tests, 0 failures; JDK 21/PostgreSQL 16 |
| `JAVA_HOME=...openjdk@21... ./gradlew build --console=plain` | exit 0 | 기본 테스트는 live eval 제외 |
| `docker compose -f docker-compose.yml config -q` | exit 0 | CI compose |
| `docker compose -f docker-compose.local.yml config -q` | exit 0 | local compose |
| `cd ai/plan-engine && uv run pytest -q && uv run ruff check . && uv run mypy app` | blocked before pytest | 시스템 Python 3.14가 `pydantic-core 2.33.2`/PyO3 최대 3.13을 초과해 wheel build 실패. Task 10 diff에는 `ai/plan-engine` 변경이 없음. |
| `git diff --check` | exit 0 | 최종 재실행 전에도 공백 오류 없음 |

테스트 수, 각 exit code, 최종 SHA, 미실행 사유는 최종 검증 완료 시 이 표와 Task 10 report에 동기화한다.
