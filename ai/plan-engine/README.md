# Ensom AI Engine — Plan (M1) · Personalization (M2)

기존 Java Spring Boot 백엔드와 독립적으로 실행되는 결정론적 Python 엔진입니다.

## 책임 범위

**Plan Engine (M1)**

- 정규화된 일정·준비시간·경로·환경 입력으로 준비 시작, 권장 출발, 목표 도착 시각 계산
- `timed_routine` 준비 항목만 준비시간에 합산
- 강수 조건에 따른 준비시간 가산 및 우산 체크리스트 병합
- 계산 근거, 실행 가능 여부, 입력 완전성 confidence 반환

**Personalization Engine (M2)**

- 완료된 일정 하나의 계획값과 실제값을 비교해 지연 원인을 분리
- 원인이 지정하는 손잡이 **하나만** 조정 (TR-05)
- 준비 시간 추정 EMA 갱신과 가드레일 적용, 보정 사유 문장 반환
- 학습 표본 자격 심사 — 부적격 표본은 오류가 아니라 `excludedFromLearning=true`

DB, 외부 지도·환경 API, 인증, FCM, 알림 스케줄링, 일정 원문 저장은 담당하지 않습니다.
`USER_PREP_ESTIMATE`·`EVENT_DELAY_REASON` 기록도 백엔드 몫입니다 — 엔진은 계산값만 돌려줍니다.

## 실행

`ai/plan-engine` 디렉터리에서 팀 테스트용 단일 의존성 파일로 설치할 수 있습니다.

```bash
python3.13 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
```

설치 후 전체 검증:

```bash
pytest
ruff check .
mypy app
```

개발 서버 실행:

```bash
uvicorn app.main:app --reload --port 8000
```

```bash
curl http://localhost:8000/health
```

## 계층 구조

```text
app/domain/plan_engine/            순수 계산 계층 (models, engine, checklist, constraints, reasons, confidence)
app/domain/personalization_engine/ 순수 보정 계층 (observation, eligibility, attribution, adjustment, reasons, engine)
app/contracts/                     Spring과 동결한 요청·응답 스키마
app/schemas/plan.py                도메인 모델을 API용으로 재노출
app/api/routes/plan.py             HTTP, 요청 식별자, 로깅
app/api/internal/                  개인화·웰니스 내부 엔드포인트
```

도메인 계층에는 FastAPI, DB, HTTP 클라이언트, `datetime.now()`, 환경변수 조회가 없습니다. 현재 시각과 모든 설정은 요청 모델로 주입합니다.

`personalization_engine`은 요청·응답 타입을 `app/contracts`에서 가져옵니다. 계약 모델이 곧 동결된 스키마이고 전송·저장 의존성이 없는 순수 Pydantic 모델이라, 도메인 순수성은 유지됩니다.

## 내부 API

```text
GET  /health
POST /internal/v1/plans/compute
POST /internal/v1/personalization/adjust
POST /internal/v1/wellness/evaluate      (M3 미구현 — STUB_MODE 게이트)
```

JSON은 Spring Backend와의 내부 계약을 위해 camelCase입니다. Python 코드는 snake_case를 사용합니다.

### 에러 계약

| 상태 | 조건 | 본문 |
|---|---|---|
| 200 | 계산 성공. 제약 위반도 여기 포함 | `PlanOutput` (`feasible=false` 가능) |
| 422 | Pydantic 검증 실패, naive datetime, 음수 분, `depart_at`인데 `fixedDepartAt` 누락, 정의되지 않은 필드 | FastAPI 검증 오류 |
| 422 | 필드는 유효하나 계산 불가 (`PlanInputError`) | `{"code": "INVALID_PLAN_INPUT", "message": "..."}` |
| 500 | 내부 오류 | `{"code": "INTERNAL_ERROR", "message": "..."}` — stack trace 미노출 |

입력 모델은 `extra="forbid"`입니다. 계약에 없는 필드를 보내면 422가 되므로, 필드를 추가할 때는 엔진과 함께 변경해야 합니다.

권장 호출 타임아웃은 connect 1초, read 2초입니다. 외부 I/O가 없어 정상 상황에서는 즉시 반환됩니다(측정 p95 약 0.01ms).

## 계산 규칙

`arrive_by`:

```text
target_arrive_at = event.starts_at - arrival_buffer
recommended_depart_at = target_arrive_at - travel - traffic_buffer
prep_start_at = recommended_depart_at - estimated_prep - extra_prep - routine
```

`depart_at`:

```text
recommended_depart_at = event.fixed_depart_at
target_arrive_at = recommended_depart_at + travel
prep_start_at = recommended_depart_at - estimated_prep - extra_prep - routine
```

`depart_at`은 출발 시각이 고정이므로 도착 여유와 교통 버퍼를 사용하지 않습니다. 이때 `breakdown`의 두 값은 0으로 보고하고, 입력값은 근거 문장에 남깁니다. 덕분에 `breakdown`만으로 세 시각을 재구성할 수 있습니다.

모든 datetime은 timezone offset을 포함해야 합니다. 계획이 이미 늦어도 계산값을 현재 시각으로 강제 변경하지 않고 `feasible=false`와 근거를 반환합니다.

### 체크리스트 병합

우선순위는 `rule` > `event_item` > `weather`입니다. 이름은 앞뒤 공백 제거, 연속 공백 정리, 대소문자 통일까지만 정규화하며 의미 추론은 하지 않습니다. 따라서 `우 산`과 `우산`은 별개 항목입니다.

두 불변식을 보장합니다.

- 체크리스트 `appliedMinutes` 합 = `breakdown.personalRoutineMinutes`
- `appliedMinutes > 0`은 `timed_routine`에만 존재

## 개인화 보정 규칙 (M2)

### 관측 분해

```text
Δprep    = actualPrepStartedAt − prepStartAt
Dactual  = actualPrepFinishedAt − actualPrepStartedAt      (없으면 actualDepartedAt 기준)
Δdepart  = actualDepartedAt − recommendedDepartAt
transit  = (actualArrivedAt − actualDepartedAt) − travelMinutes
```

계획 창에는 추정 준비시간뿐 아니라 루틴·강수 가산이 함께 들어 있습니다. 그래서 창의 나머지
`fixed = (recommendedDepartAt − prepStartAt) − estimatedPrepMinutes`를 먼저 뺀 값만 EMA에 넣습니다.
그러지 않으면 루틴 시간이 매일 추정치에 접히면서 추정이 계속 부풀어 오릅니다.

### 원인 → 손잡이 라우팅

| 신호 | 측정 | 원인 | 조정 대상 | 준비 추정 |
|---|---|---|---|---|
| 시작 지연 | `max(0, Δprep)` | `prep_late` | `notification_lead` | 불변 |
| 준비 초과 | `max(0, 관측 − 추정)` | `prep_overrun` | `prep_estimate` | **EMA 갱신** |
| 출발 지체 | 준비 완료·권장 출발 이후 남은 시간 | `depart_late` | `departure_lead` | 불변 |
| 교통 오차 | `max(0, transit)` | `traffic` | `traffic_buffer` | 불변 |
| 일정 변경 | `eventModifiedAfterPlan` | `external` | 없음 | 학습 제외 |

`confidence`는 각 신호가 전체 지연에서 차지하는 비율입니다. `attributionMinSignalMinutes`(기본 3분)
미만은 잡음으로 버립니다. 동률이면 인과 사슬의 앞쪽(`prep_late → prep_overrun → depart_late → traffic`)이
이깁니다. 뒤 원인은 앞 원인이 만들어낸 결과이고, TR-05가 경고한 추정 손잡이가 마지막에 오기 때문입니다.

모든 후보는 `candidates`로 함께 반환하므로 백엔드는 `EVENT_DELAY_REASON`에 복수 행을 남길 수 있습니다.
**움직이는 손잡이는 언제나 하나입니다.**

### 추정 갱신과 가드레일

```text
P ← (1−α)·P + α·관측                           α = prepEmaAlpha (0.30)
arrival_result ∈ {late, rushed} → α × 1.5      실패가 더 강한 신호
arrival_result = early          → α × 0.7      줄이는 방향은 신중히
1회 변화 ≤ maxStepMinutes (15)                  콜드 스타트는 coldStepMinutes (20)
P ∈ [prepFloorMinutes, 시드 × prepCeilingRatio] = [10, 시드×2]
```

`sampleCount < coldStartSampleThreshold(3)`이면 시드를 유지합니다. 예외는 첫 명확한 실패
(`late`/`rushed` + `prep_overrun`) 1회뿐이고, 그것도 `coldStartAdjusted=true`가 오면 더는 적용하지 않습니다.

계산 결과가 P와 같으면 `adjustedKnob`은 `none`입니다 — 백엔드가 쓸 것이 없다는 뜻입니다.
교통 버퍼도 같은 EMA와 같은 step 상한을 씁니다. TRD §6.2는 손잡이 이름만 정하고 공식을 주지 않았으므로,
두 번째 갱신 규칙을 새로 만드는 대신 이미 합의된 규칙을 재사용했습니다.

### 학습 표본 자격 (§6.1 · MODEL-01)

부적격은 오류가 아닙니다. 200으로 `excludedFromLearning=true`와 `exclusionReasons`를 돌려주므로
백엔드가 제외율을 가드레일 지표로 집계할 수 있습니다.

| 코드 | 조건 |
|---|---|
| `incomplete_timestamps` | 준비 시작 또는 출발 기록 누락 |
| `clock_skew` | `abs(clockSkewSeconds) > clockSkewToleranceSeconds` (120) |
| `arrival_result_unknown` | 도착 결과 미기록 또는 정의되지 않은 값 |
| `auto_manage_excluded` | 자동 관리 제외 일정 |
| `prep_duration_outlier` | 관측 준비 소요가 `(0, prepOutlierMaxMinutes]` 밖 |
| `geo_confidence_low` | `resultSource='geo'`인데 `resultConfidence < geoMinConfidence` |
| `event_modified` | 계획 이후 일정 삭제·시각 변경 → `cause=external` |
| `learning_reverted` | 사용자가 되돌린 표본 — 영구 제외 (§6.4) |

### degraded 코드

`seed_fallback`, `prep_finish_unknown`, `transit_unknown`, `cold_start_hold`, `step_limited`,
`floor_clamped`, `ceiling_clamped`, `config_fallback`. "무엇이 없어 무엇을 가정했는지"만 남깁니다.

### 보정 사유 문장

`app/domain/personalization_engine/reasons.py`의 템플릿 표에만 존재하고, 엔진은 숫자 슬롯만 치환합니다
(TR-09). 일정 제목·장소·체크리스트 항목명은 계약에 애초에 들어오지 않습니다.

## 설정 키

Spring Backend가 `ENGINE_CONFIG`를 읽어 요청으로 전달합니다. AI 서버는 설정을 저장하지 않습니다.

| 설정 키 | 요청 필드 | 기본값 | 계산 사용 |
|---|---|---|---|
| `SEED_FALLBACK_MIN` | `config.seedFallbackMinutes` | 30 | 사용 |
| `RAIN_THRESHOLD_PERCENT` | `config.rainThresholdPercent` | 60 | 사용 |
| `RAIN_EXTRA_PREP_MIN` | `config.rainExtraPrepMinutes` | 5 | 사용 |
| `ARRIVAL_BUFFER_DEFAULT_MIN` | `config.arrivalBufferDefaultMinutes` | 10 | 미사용 |
| `TRAFFIC_BUFFER_DEFAULT_MIN` | `config.trafficBufferDefaultMinutes` | 5 | 미사용 |

도착 여유와 교통 버퍼는 요청 최상위의 `arrivalBufferMinutes`, `trafficBufferMinutes`(필수)를 사용합니다. 위 두 기본값은 전체 설정 행을 그대로 전달할 수 있게 계약에만 존재하며, 누락돼도 `config_fallback`으로 판정하지 않습니다.

### 개인화 엔진 설정 키

| 설정 키 | 요청 필드 | 기본값 | 근거 |
|---|---|---|---|
| `PREP_EMA_ALPHA` | `config.prepEmaAlpha` | 0.30 | 부록 A.1 |
| `LATE_WEIGHT` | `config.lateWeight` | 1.50 | 부록 A.1 |
| `EARLY_WEIGHT` | `config.earlyWeight` | 0.70 | 부록 A.1 |
| `MAX_STEP_MIN` | `config.maxStepMinutes` | 15 | 부록 A.1 |
| `COLD_STEP_MIN` | `config.coldStepMinutes` | 20 | 부록 A.1 |
| `PREP_FLOOR_MIN` | `config.prepFloorMinutes` | 10 | 부록 A.1 |
| `PREP_CEIL_RATIO` | `config.prepCeilingRatio` | 2.0 | 부록 A.1 |
| `SEED_FALLBACK_MIN` | `config.seedFallbackMinutes` | 30 | 부록 A.1 |
| **미등록** | `config.coldStartSampleThreshold` | 3 | §6.2 본문 |
| **미등록** | `config.clockSkewToleranceSeconds` | 120 | §6.1 · TR-02 |
| **미등록** | `config.prepOutlierMaxMinutes` | 240 | §6.1 |
| **미등록** | `config.geoMinConfidence` | 0.60 | §6.1 · 부록 A.3 `AUTO_CONF` |
| **미등록** | `config.attributionMinSignalMinutes` | 3 | M2 신설 |

`model_version`을 제외한 위 필드 중 하나라도 누락되면 `degraded`에 `config_fallback`이 남습니다.
**미등록** 다섯 개는 TRD 본문에는 값이 있으나 부록 A 파라미터 레지스트리에 키가 없습니다 — `engine_config`
행에 추가할 키 이름을 김민형과 확정해야 합니다.

## 버전과 로깅

응답과 `/health`는 `calcVersion`을 반환합니다. 값은 `app/domain/plan_engine/version.py`에서 관리하며, 계산 규칙이나 근거 의미가 바뀌면 올립니다. Spring은 이 값을 계획 리비전과 함께 저장해 과거 계획의 재현성과 실험 단위 비교를 유지합니다.

개인화는 `modelVersion`을 따로 관리합니다(`app/domain/personalization_engine/version.py`, `/health`의 `personalizationModelVersion`). 보정 규칙·가드레일·사유 문장 의미가 바뀌면 올리고, Spring은 `USER_PREP_ESTIMATE.model_version`에 함께 저장합니다. 과거 보정을 소급 재계산하지 않습니다(D15).

로그에는 `request_id`, `calc_version`, `anchor_mode`, `feasible`, `prediction_confidence`, `degraded`, 준비 항목 개수, 소요 시간만 남깁니다. 개인화 로그는 `request_id`, `model_version`, `cause`, `knob`, `excluded`, `exclusion_reasons`, `degraded`, 소요 시간만 남깁니다 — `event_id`와 관측 타임스탬프는 기록하지 않습니다. 일정 제목, 체크리스트 항목명, 위치, 토큰은 기록하지 않으며 예외 로그도 traceback 없이 예외 타입만 남깁니다. 요청자는 `X-Request-Id` 헤더로 추적 ID를 넘길 수 있고, 없으면 서버가 생성합니다.

## 팀 확인이 필요한 결정

- `predictionConfidence`: 프롬프트 §15는 준비시간 누락을 MID와 LOW에 동시에 두어 모순됩니다. 현재는 준비시간 누락 또는 오래된 경로를 LOW로 처리하며, 변경 지점은 `confidence.py` 한 곳입니다.
- 작업 브랜치: 프롬프트는 `feat/ai-plan-engine`(기준 `develop`)이지만 저장소 기본 브랜치는 `dev`입니다.
- `/internal/v1/plans/compute`에는 인증이 없습니다. 배포 시 내부 네트워크로만 노출하거나 서비스 토큰을 도입할지 결정이 필요합니다.

M2에서 새로 생긴 결정 사항입니다.

- **`actual_prep_finished_at` 컬럼 (김민형)** — ERD v3의 `EVENT_EXECUTION`에는 준비 완료 시각이 없습니다. 타임스탬프 3개로는 `Δdepart ≡ Δprep + Dactual − 창`이 항등식이라 `depart_late`와 `prep_overrun`을 원리적으로 구분할 수 없습니다. TRD §6.2가 `adjustment_reason` 컬럼 추가를 권고한 것과 같은 성격의 컬럼 추가 권고입니다. 없으면 엔진은 `depart_late`를 절대 반환하지 않고 `prep_finish_unknown`을 남깁니다.
- **EMA 대상에서 루틴 시간 분리** — TRD §6.2는 `Dactual`을 그대로 EMA에 넣지만, 계획 창에는 루틴·강수 가산이 함께 들어 있어 그대로 넣으면 추정이 매일 부풀어 오릅니다. 창의 나머지를 먼저 빼는 것으로 해소했고 변경 지점은 `observation.py` 한 곳입니다.
- **동률 tie-break 순서** — 인과 사슬 앞쪽 우선으로 정했습니다(`attribution.py`의 `CAUSAL_ORDER`).
- **교통 버퍼 갱신 공식** — TRD가 손잡이만 지정하고 공식을 주지 않아 준비 추정과 같은 EMA·step 상한을 재사용했습니다.
- **계약 추가 필드** — 입력 6개·출력 4개를 Optional/기본값으로만 추가했습니다(계약 문서 §10 non-breaking). Spring 측 Jackson이 미지의 응답 필드에서 실패하지 않도록 `FAIL_ON_UNKNOWN_PROPERTIES=false` 확인이 필요합니다.
- **미등록 설정 키 5종** — 위 설정 표 참고.

## Spring Backend 인계 주의사항

- Provider 경로의 초 단위 값을 AI 요청의 분 단위로 먼저 정규화합니다. 이동 시간을 과소평가하지 않도록 올림을 권장합니다.
- `Event` 원문 제목·본문·참석자·정확한 위치는 전송하지 않습니다.
- 기존 전역 Jackson `SNAKE_CASE` 설정은 변경하지 않고 AI 호출 전용 camelCase DTO를 사용합니다.
- 요청·응답 예시는 `examples/plan_compute_request.json`, `examples/plan_compute_response.json`입니다.
- 개인화 골든 케이스는 `tests/golden/personalization/*.json`에 입력·기대 출력이 함께 들어 있어 Java DTO 역직렬화 검증에 그대로 쓸 수 있습니다.
- 개인화 응답의 `previousValue`/`newValue`는 **`adjustedKnob`이 지정한 대상의 값**입니다. `prep_estimate`면 준비 시간 추정(분), `traffic_buffer`면 교통 버퍼(분)이고, `notification_lead`·`departure_lead`는 값이 `null`입니다.
- `excludedFromLearning=true`는 오류가 아닙니다. `EVENT_DELAY_REASON`·`USER_PREP_ESTIMATE`를 쓰지 말고 제외 사유만 지표로 집계하십시오.
- OpenAPI 스키마는 `/openapi.json`에서 확인합니다.
- M1 API는 인증·DB·외부 API를 직접 처리하지 않습니다.

## 의존성 관리

로컬·CI 테스트는 `requirements.txt`, 컨테이너 이미지는 `pyproject.toml`을 사용합니다. 두 파일의 버전 일치는 `tests/test_dependency_contract.py`가 검증합니다.
