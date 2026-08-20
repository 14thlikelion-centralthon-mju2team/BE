# 외부 AI 모델 접목 지점 분석 — 구현 지침

- 대상 저장소: `/Users/leejiho/Desktop/BE` (Spring Boot BE + `ai/plan-engine` FastAPI)
- 분석 기준: PRD v0.4.3, TRD v4.0, API 명세, MileStone, ERD, `ai/plan-engine/docs/internal-engine-contracts.md`, 그리고 실제 코드
- 전제: **현재 코드는 불안정하며 시간이 부족하다. 기존 정상 기능에 어떤 영향도 주지 않는 접점만 채택한다.**

---

## 0. 3줄 결론

1. 붙일 곳은 이미 설계되어 비어 있다 — **`EVENT_CLASSIFICATION_REVIEW` (일정 분류)** 가 유일한 1순위 정식 슬롯이다. 테이블·엔티티·사용자 확인 UI·응답 API가 전부 존재하는데 **레코드를 생성하는 코드만 없다.**
2. `openai.*` 설정은 `application.yaml`에 이미 있으나 **이를 사용하는 Java 코드는 한 줄도 없다.** 즉 새 외부 호출을 추가하는 것이지 기존 호출을 바꾸는 것이 아니다 → 회귀 위험이 구조적으로 낮다.
3. **사용자에게 보이는 문구를 LLM이 생성하는 것은 문서상 명시적 금지**다(TR-09). 시각 계산·WIS·개인화 보정 대체도 금지다. 분류/제안/정렬 같은 "실패해도 현상 유지"인 지점만 허용한다.

---

## 1. 절대 준수 가드레일 (문서 근거)

| # | 규칙 | 근거 |
|---|---|---|
| G1 | **사용자가 지정한 값이 자동 판단을 항상 이긴다.** AI 결과로 사용자 입력 필드를 덮어쓰지 말 것 | TRD 절대 원칙 5 / 화면설계서 CAL-01 "자동 분류가 절대 덮어쓰지 않음" |
| G2 | **외부 일정 제목 원문·전체 이동 경로는 보관 금지.** 분류 입력으로만 잠깐 쓰고 폐기 | TRD 절대 원칙 8, ERD `title_snapshot` 주석 |
| G3 | **자유 생성 LLM 문구 금지.** 사용자 노출 카피는 승인 템플릿에서만 선택 | TR-09, PRD §14.8, MileStone M4 "자유 생성 모델 없이 승인 템플릿만" |
| G4 | **외부 의존성 부재/실패는 오류가 아니라 degraded.** 핵심 흐름을 막지 않는다 | TRD 절대 원칙 3, TR-11.5 |
| G5 | **분류 신뢰도가 낮으면 자동 확정하지 말고 사용자에게 1회 질문.** 임계값 0.7 | 화면연결명세서 S-11 "신뢰도 < 0.7", PRD §12 |
| G6 | **민감·규제 품목은 추천하지 않는다** (담배·주류 등). 복용약은 민감 처리 강제 | PRD §11.5, 화면설계서 ONB |
| G7 | **좌표·userId·전체 경로·민감 준비 항목명은 지표/로그/외부 전송 payload에 넣지 않는다. 제목 원문은 일정 분류용 OpenAI 요청에만 예외적으로 허용하며 로그·지표·tracing에는 남기지 않는다** | TR-10, `ProductEventService` 주석, 개발 설계 D-01(2026-08-20 사용자 승인) |
| G8 | 답변 기록 시 `title_snapshot`은 반드시 같은 트랜잭션에서 NULL 처리 | DB 제약 `ck_title_purged` (V6:325-329) |

---

## 2. 채택 권고 — 안전 등급별

### ✅ S1 (1순위, 지금 바로 안전) — 일정 분류기: `EVENT_CLASSIFICATION_REVIEW` writer

**왜 가장 안전한가**

- 스키마가 AI용으로 이미 설계되어 있다: `V6__ensom_v3_1_schema.sql:311-333`에 `model_version`, `classification_confidence`(0~1 CHECK), `title_snapshot`, `suggested_value` 컬럼 존재
- 엔티티 `EventClassificationReview.java`, 조회 `EventClassificationReviewRepository`, 사용자 응답 처리 `EventService.answerReview()` (API `POST /events/{id}/review`) 모두 **이미 구현되어 동작 중**
- **그러나 review 레코드를 생성하는 코드가 어디에도 없다** (전수 grep 확인). 즉 이 기능은 현재 "질문이 없으면 아무 일도 안 일어남" 상태다
- FE도 CAL-05 / S-11 분류 확인 시트가 구현되어 있다
- 실패 시 결과: review가 안 생김 → `location_state`는 현재와 동일하게 `undecided` 유지 → **현상 그대로**

**연결 지점**

`CalendarSyncService.processEvent()`의 새 일정 생성 분기. 현재 코드는 다음과 같이 고정값을 넣고 끝난다.

```java
// CalendarSyncService.java — 새 일정 생성 분기 (현재)
.locationState("undecided")
...
// displayLabel: 외부 일정 제목 원문은 저장하지 않음 (TRD 절대 원칙 8)
// 분류 후 폐기 — displayLabel은 사용자가 승인할 때까지 null
```

여기에 **분류 시도 → review 생성**만 추가한다. `Event`의 다른 필드는 건드리지 않는다.

**구현 경계 (반드시 지킬 것)**

- 이 서비스는 이미 `@Scheduled(fixedDelay = 300_000)` 백그라운드 경로다. **사용자 요청 경로가 아니므로 지연이 사용자에게 노출되지 않는다.** 동기 API에 절대 넣지 말 것
- 1차 출시는 review-only다. 신뢰도와 관계없이 유효한 분류 결과는 review만 남기고 `location_state`를 **직접 확정하지 않는다.** 자동 반영은 별도 품질 검증과 후속 설계 승인 전까지 금지한다(개발 설계 D-02)
- 이미 답변된 일정(`answeredAt != null`)이나 사용자가 지정한 일정은 재분류하지 않는다 (G1)
- 신규 review는 `title_snapshot=NULL`, `title_purged_at=asked_at`으로 처음부터 원문을 저장하지 않는다. 24시간 purge는 기존·비정상 행의 방어적 안전망으로 유지하고 review 행은 90일 후 삭제한다(개발 설계 D-05)
- LLM 응답은 **enum 화이트리스트로만 파싱**한다: `questionType`은 현재 `is_online`만 지원(`EventService.QUESTION_TYPE_IS_ONLINE`), `userAnswer`는 `online` / `offline`. 그 외 값은 버린다
- `confidence`는 0~1 범위 밖이면 저장하지 않는다 (`ck_classification_confidence`)

---

### ✅ S2 (2순위, 안전) — 준비물 추천 칩 정렬/선별 (읽기 전용)

- 대상: 온보딩·준비 항목 추천 칩 순서
- **저장하지 않고 응답에만 반영**한다. `USER_PREP_RULE`을 AI가 쓰지 않는다
- 반드시 **기존 승인 카탈로그 내에서 순서만** 바꾼다. 새 품목명을 LLM이 생성하면 G3·G6 위반
- 실패 시: 기존 고정 순서 그대로 반환
- 민감·규제 품목 필터를 AI 출력 뒤에 한 번 더 적용한다 (G6)

---

### ⚠️ S3 (조건부) — 내부 운영 요약 (사용자 비노출)

- 대상: `WellnessOperationalMetricsScheduler`가 남기는 일일 집계 로그의 자연어 요약
- 조건: **집계 수치만 입력.** userId·좌표·제목·항목명 금지 (G7). `WellnessOperationalMetrics`는 이미 이 필드들을 의도적으로 갖고 있지 않다
- 사용자에게 절대 노출하지 않는다. 노출하는 순간 G3 위반

---

## 3. 금지 접점 (붙이면 안 되는 곳)

| 대상 | 이유 |
|---|---|
| `prepStartAt` / `recommendedDepartAt` / `targetArriveAt` 등 **시각 계산 대체** | 결정론 계약 위반. `input_hash` 기반 리비전 억제와 golden/property 테스트가 전제(`test_plan_engine.py`, `test_input_hash.py`). 비결정적 출력은 재계산마다 새 리비전을 만들어 알림 폭증으로 이어진다 |
| **WIS/RLS/DWL 점수 산출 대체** | 가중치·임계·밴드가 `engine_config`와 golden 테스트로 고정됨. 알림 발송 게이트와 직결 |
| **개인화 EMA 보정 대체** | 가드레일(step limit/floor/ceiling)과 되돌리기 계약 위반 위험 |
| **사용자 노출 문구 생성** (푸시 본문, 일일 카드, 보정 사유) | TR-09 명시 금지. `DailySummaryService:309` 주석 "승인된 템플릿에서만 문구를 고른다(자유 생성 LLM 미사용)", `WellnessEventSchedulerService:240` 동일 |
| **동기 요청 경로 삽입** — `POST /events`, `POST /plans/{id}/actions`, `GET /me/bootstrap` | 사용자 체감 지연·타임아웃 전파. 특히 `POST /plans/{id}/actions`는 상태 전이·개인화·지표를 한 트랜잭션에서 처리 |
| **트랜잭션 내부에서 LLM 호출** | DB 커넥션 점유. 코드베이스가 이미 이를 금지하는 패턴을 씀(`CalendarService:73`, `AuthService:165` 주석) |
| **좌표·전체 경로·민감 항목명 외부 전송** | G2, G7 |
| 인증/인가/결제 판단 | 해당 없음 + 보안 경계 |

---

## 4. 필수 구현 패턴 (이 저장소의 기존 안전 패턴을 그대로 복제할 것)

### 4.1 키 없으면 빈 자체가 안 뜨게 (가장 중요)

`OdsayRouteProvider:31`이 쓰는 패턴을 그대로 따른다. 키가 없으면 AI 코드가 아예 로딩되지 않아 **기존 동작이 100% 보존**된다.

```java
@Primary
@ConditionalOnExpression("!'${openai.api-key:}'.isBlank()")
public class OpenAiEventClassifier implements EventClassifier { ... }
```

- 기본(no-op 또는 규칙 기반) 구현을 인터페이스의 기본 빈으로 두고, 키가 있을 때만 AI 구현이 `@Primary`로 앞선다
- `application.yaml`에 이미 `openai.api-key: ${OPENAI_API_KEY:}`(기본 빈 문자열)가 있어 **운영에 키를 넣지 않는 한 프로덕션 동작이 바뀌지 않는다**

### 4.2 실패는 `Optional` + `log.warn`으로 흡수

`PlanEngineClient.compute()`가 정확한 참조 구현이다.

```java
public Optional<Result> classify(Input input) {
    try {
        ...
    } catch (RestClientException | JsonProcessingException e) {
        log.warn("분류 호출 실패 — 분류 생략하고 진행합니다. cause={}", e.toString());
        return Optional.empty();
    }
}
```

- 예외를 호출자에게 전파하지 않는다
- 조용히 삼키지 않고 WARN 로그는 남긴다 (기존 주석의 의도)

### 4.3 timeout 필수 지정

`PlanEngineClientConfig:23-24`(connect 1s / read 2s), `OdsayRestClientConfig`, `EnvironmentRestClientConfig`와 같은 방식으로 **전용 `RestClient` 빈**을 만든다. 설정 키는 이미 존재한다.

```
openai.connect-timeout-ms: 5000
openai.read-timeout-ms: 10000
```

- 전역 `RestClient`(`RestClientConfig.restClient()`)는 timeout이 없다. **재사용 금지** — 무한 대기로 스케줄러가 묶일 수 있다
- 백그라운드 경로이므로 read timeout은 넉넉해도 되지만, 반드시 유한해야 한다

### 4.4 부수 효과는 별도 트랜잭션 + 실패 흡수

`ProductEventService` + `ProductEventWriter` 조합이 참조 구현이다(테스트: `별도_writer의_DB_실패는_호출자에게_전파하지_않는다`). AI 결과 저장 실패가 캘린더 동기화 트랜잭션을 롤백시키면 안 된다.

### 4.5 출력 검증은 화이트리스트

- enum/범위 밖 값은 **버린다**(예외 아님)
- 모델이 지시를 무시하거나 프롬프트 인젝션(캘린더 제목에 악의적 문장이 들어올 수 있다)으로 이상 응답을 줄 수 있다고 가정한다. **일정 제목은 신뢰할 수 없는 입력이다**
- `modelVersion`을 반드시 기록한다(컬럼 이미 존재) — 사후 추적·되돌리기의 근거

---

## 5. 검증 기준 (머지 전 통과해야 할 항목)

1. **키 미설정 시 무영향**: `OPENAI_API_KEY`가 비어 있을 때 `./gradlew build` 전체 테스트가 그대로 통과하고, AI 빈이 로딩되지 않음을 확인
2. **호출 실패 시 무영향**: 외부 호출이 예외/timeout일 때 캘린더 동기화가 정상 완료되고 일정이 기존과 동일하게 생성됨을 테스트로 고정
3. **이상 응답 무영향**: 화이트리스트 밖 값·범위 밖 confidence·null 응답에서 review가 생성되지 않거나 안전한 값만 저장됨
4. **사용자 우선 보존**: 사용자가 지정한 `location_state`를 AI가 덮어쓰지 않음 (G1)
5. **원문 폐기**: 답변 시 `title_snapshot`이 NULL이 되고 `title_purged_at`이 채워짐. `ck_title_purged` 위반 없음
6. **결정론 영역 불변**: 기존 plan/wellness/personalization golden·property 테스트 전부 그대로 통과 (수정 금지)
7. **지연 무노출**: 동기 API 응답 경로에 새 외부 호출이 없음을 코드 리뷰로 확인
8. **전송 최소화**: 외부 payload는 분류 대상 `summary` 하나만 허용하며 좌표·userId·장소·설명·회의 URL·전체 경로·민감 항목명이 없음

---

## 6. 권고 작업 순서 (시간 부족 기준)

1. `EventClassifier` 인터페이스 + no-op 기본 구현 추가 (동작 변화 0)
2. 전용 timeout `RestClient` 빈 + `@ConditionalOnExpression` AI 구현 추가 (키 없으면 미로딩)
3. `CalendarSyncService.processEvent()` 새 일정 분기에 **review 생성만** 추가 (`location_state` 미변경)
4. 화이트리스트 파싱·review-only·실패 흡수·동시성 테스트 작성
5. `title_snapshot` 방어적 24시간 purge와 review 90일 삭제 배치를 별도 커밋으로 분리

S2·S3는 시간이 남을 때만 착수한다. **S1만으로도 "AI가 붙었다"는 요구를 충족하면서 회귀 위험이 실질적으로 0에 가깝다.**

---

## 7. 참고: 확인된 현재 상태 요약

| 항목 | 상태 |
|---|---|
| `openai.*` 설정 | `application.yaml:43-48`에 존재. **사용 코드 없음** |
| OpenAI 클라이언트 구현 | 없음 |
| `EVENT_CLASSIFICATION_REVIEW` 테이블 | 존재 (V6:311-333), AI 컬럼 포함 |
| review 생성 코드 | **없음** ← 접목 지점 |
| review 응답 처리 | 구현됨 (`EventService.answerReview`, `POST /events/{id}/review`) |
| `ai/plan-engine` | 순수 결정론 엔진. LLM 없음. 도메인 계층에 `datetime.now()`·HTTP·DB 없음 |
| 실패 흡수 패턴 | `PlanEngineClient`, `WellnessEngineClient`, `PersonalizationEngineClient`, `ProductEventService`, `OdsayRouteProvider` |
| 키 게이팅 패턴 | `OdsayRouteProvider`, `KmaEnvironmentProvider`, `AirKoreaUvEnvironmentProvider`, `StubEnvironmentProvider` |
| 사용자 노출 문구 | 전부 승인 템플릿 (`WellnessActionCatalog`, `templates.py`, `reasons.py`) — LLM 생성 금지 |
