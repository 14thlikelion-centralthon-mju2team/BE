# Ensom AI Engine — Plan (M1) · Personalization (M2) · Wellness (M3)

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

**Wellness Engine (M3)**

- 환경·야외 노출 입력을 정규화해 WIS(일정 웰니스 우선순위)와 밴드 산출
- 승인된 행동 카탈로그에서 준비 카드 행동 최대 3개 선택, 사용자 준비 항목과 병합
- 웰니스 푸시 4중(실질 6중) 게이트 판정 — 통과 시 행동 1건만 예약
- RLS(촉박함 부담)와 DWL(일일 부담) 산출, 일일 마무리 카드 시나리오 선택

**WIS·RLS·DWL은 알림 우선순위 값이고 건강 점수가 아닙니다** (절대 원칙 3). 진단·치료·복용량·효능·피부
판정은 판단할 데이터 자체가 이 패키지에 들어오지 않습니다. 사용자에게 나가는 문구는 승인 템플릿뿐입니다.

DB, 외부 지도·환경 API, 인증, FCM, 알림 스케줄링, 일정 원문 저장은 담당하지 않습니다.
`USER_PREP_ESTIMATE`·`EVENT_DELAY_REASON`·`PLAN_WELLNESS_SCORE` 기록도 백엔드 몫입니다 —
엔진은 계산값만 돌려줍니다.

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
app/domain/wellness_engine/        순수 점수 계층 (quantize, normalize, wis, actions, arming, rls, dwl, templates, engine)
app/contracts/                     Spring과 동결한 요청·응답 스키마
app/schemas/plan.py                도메인 모델을 API용으로 재노출
app/api/routes/plan.py             HTTP, 요청 식별자, 로깅
app/api/internal/                  개인화·웰니스 내부 엔드포인트
```

도메인 계층에는 FastAPI, DB, HTTP 클라이언트, `datetime.now()`, 환경변수 조회가 없습니다. 현재 시각과 모든 설정은 요청 모델로 주입합니다.

`personalization_engine`과 `wellness_engine`은 요청·응답 타입을 `app/contracts`에서 가져옵니다. 계약 모델이 곧 동결된 스키마이고 전송·저장 의존성이 없는 순수 Pydantic 모델이라, 도메인 순수성은 유지됩니다.

## 내부 API

```text
GET  /health
POST /internal/v1/plans/compute
POST /internal/v1/personalization/adjust
POST /internal/v1/wellness/evaluate
POST /internal/v1/wellness/rush-load
POST /internal/v1/wellness/daily-summary
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

## 웰니스 규칙 (M3)

### 점수

```text
WIS = min(100, 100 × (0.35·U + 0.25·P + 0.20·T + 0.20·O) × M)   일정 웰니스 우선순위
RLS = min(100, 100 × (0.45·Dp + 0.35·Dd + 0.20·E))              촉박함 부담
DWL = 0.6 × (일정별 WIS의 야외시간 가중평균) + 0.4 × (일정별 RLS 평균)
```

### 정규화와 부재 처리

| 항 | 정규화 | 데이터 부재 시 |
|---|---|---|
| U 자외선 | 0→0 · 6→0.6 · 8→0.8 · 10 이상→1.0 | U=0, `uv_unavailable` |
| P 대기질 | 좋음 0 · 보통 0.25 · 나쁨 0.7 · 매우나쁨 1.0 | P=0, `pm_unavailable` |
| T 체감온도 | 쾌적(5~28℃) 0 → 폭염 33℃·한파 −12℃에서 1.0 선형, heavy rain이면 +0.3 후 클램프 | T=0, `temp_unavailable` |
| O 야외 노출 | `min(1, 야외분 / 120)` | **WIS 자체를 생략**, `outdoor_unavailable` |
| M 관심사 | 기본 1.0, 관심 항목이 오늘 환경과 관련되면 1.25 | 1.0 |

대기질은 원값(µg/m³)이 아니라 **등급**을 정규화합니다. 원값에서 등급을 유도하면 이 엔진이 대기질
기준을 정하는 셈이 되기 때문입니다. 백엔드가 제공자 등급을 `good`/`moderate`/`bad`/`very_bad`로
매핑해 보냅니다.

환경 전체가 없으면 웰니스만 생략하고 시간 계획은 Plan Engine에서 정상 계산됩니다 (§11.5).

### 양자화 (inputHash와 공유, §5.5)

```text
rain : none | light(≥30%) | heavy(≥60%)
uv   : low  | high(≥6)
pm   : good | bad | veryBad          좋음과 보통은 같은 결정을 낳으므로 한 버킷
temp : cold | mild | hot             + 일교차 플래그
```

### 밴드와 행동

| 밴드 | 범위 | 동작 |
|---|---|---|
| low | 0~39 | 조용히 — 행동도 푸시도 없음 |
| mid | 40~69 | 준비 카드 행동 `midBandActionCap`개 (기본 2) |
| high | 70~100 | 준비 카드 행동 3개 + 푸시 후보 |

행동 매핑은 PRD §14.6 규칙표 그대로입니다.

| 조건 | 외출 전 (`actions`) | 일정 중 (푸시 후보) |
|---|---|---|
| 자외선 + 야외 이동 | `uv_protect` | `uv_reapply` |
| 미세먼지 나쁨 이상 | `pm_mask` | `pm_recheck` |
| 폭염·높은 체감온도 | `temp_heat_prep` | `hydration_intake` |
| 한파·큰 일교차 | `temp_cold_prep` | 없음 (PRD "추가 푸시 없음") |
| 강수 | `rain_gear` | `hydration_intake` (노출이 길면) |

`actions`는 준비 카드용 외출 전 행동만 담고, 일정 중 행동은 `armedActionCode`로 따로 보고합니다 —
같은 항목을 카드와 푸시에 두 번 세지 않기 위해서입니다. 순위는 WIS 기여도 내림차순이고, 동률이면
`uv > pm > temp > rain > hydration` 순입니다. 강수는 §7.2가 T에 접어 넣었으므로 체감온도와 기여도를
공유합니다.

비만 오는 날은 WIS가 낮아 카드에 뜨지 않습니다. 우산은 Plan Engine의 강수 체크리스트가 담당하므로
빠지지 않습니다 (§5.4 · 절대 원칙 6 알림 예산).

사용자가 이미 등록한 항목과 제안이 같으면 1건으로 병합하고, 사용자 항목의 `sourceType='rule'`을
유지한 채 웰니스 근거만 붙입니다. 판단은 승인 키워드 표(`templates.MERGE_KEYWORDS`)로 하며 이름은
공백·대소문자까지만 정규화합니다 — 의미 추론은 하지 않습니다. **민감 항목은 병합 대상에서 원천
제외합니다.**

### 웰니스 푸시 게이트 (TR-11)

```text
① 동의     USER_WELLNESS_PREF.is_enabled ∧ USER_SETTING.wellness_event_enabled  (둘 다 기본 false)
② 점수     wis ≥ wellnessEventMin (70) — 항목별 상향 시 85 (D9)
③ 노출     일정 진행 중 ∧ 야외 노출 잔여 ∧ 실내 전환 추정 아님
④ 주기     사용자가 정한 remindIntervalMinutes 도달 (주기 미설정이면 발사하지 않음)
⑤ 미완료   같은 action_code에 completed / stop_today 없음
⑥ 일일 상한 dailyEventCap 미소진
```

게이트 입력 기본값이 전부 보수적이라, `eventState`를 보내지 않는 호출자는 절대 푸시를 예약하지
못합니다. 막힌 경우 `armingBlockedBy`에 어느 게이트가 막았는지 남습니다.

### 일일 마무리 카드

`rushed > density > exposure > stable > default` 고정 우선순위로 시나리오를 고릅니다. 관리 일정이
0건이면 카드를 만들지 않고(`cardVisible=false`), 야외 시간을 추정조차 할 수 없으면 수치 없는 문장을
씁니다 — 숫자를 지어내지 않습니다. `dwlScore`는 응답에 포함하되 클라이언트는 표시하지 않습니다 (D5).

DWL은 한쪽 항이 없으면 0으로 취급하는 대신 남은 항의 가중치를 재정규화합니다. WIS가 없는 날은
환경 부하가 0인 날이 아니라 측정하지 못한 날이기 때문입니다.

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

### 웰니스 엔진 설정 키

부록 A.2에 있는 키(가중치 4종, `WIS_INTEREST_BOOST_MAX`, `OUTDOOR_CAP_MIN`, `WIS_BAND_CARD`/`EVENT`,
`WELLNESS_EVENT_MIN`, `WELLNESS_EVENT_MIN_RAISED`, `DAILY_EVENT_CAP_DEFAULT`, `UV_HIGH`,
`RAIN_LIGHT`/`RAIN_HEAVY`, `RLS_W_DP`/`DD`/`E`, `DWL_W_WIS`/`DWL_W_RLS`, `DWL_BANDS`)는 이름 그대로
쓰고, 아래는 TRD 본문에만 값이 있어 키 이름이 미정입니다.

| 요청 필드 | 기본값 | 근거 |
|---|---|---|
| `config.uvFullLoadIndex` | 10 | §7.2 U 정규화 포화점 |
| `config.pmLoadModerate` / `Bad` / `VeryBad` | 0.25 / 0.70 / 1.00 | §7.2 P 등급 부하 |
| `config.comfortMinCelsius` / `comfortMaxCelsius` | 5 / 28 | §7.2 쾌적 구간 |
| `config.heatExtremeCelsius` / `coldExtremeCelsius` | 33 / −12 | §7.2 "폭염·한파 경계" — 숫자 미명시, 제안값 |
| `config.rainThermalBonus` | 0.30 | §7.2 강수 heavy 가산 |
| `config.tempSwingFlagCelsius` | 10 | §7.2 일교차 플래그 — 기준 미명시 |
| `config.midBandActionCap` | 2 | PRD §14.3 "행동 1~2개" |
| `config.rlsDelayFullLoadMinutes` | 30 | PRD §14.4 Dp·Dd 정규화 척도 미명시, 제안값 |
| `config.rlsCriticalAlertFullCount` | 2 | PRD §14.4 E 정규화 척도 미명시, 제안값 |
| `config.cardRushedRls` | 70 | §7.5 rushed 판정 기준 미명시 |
| `config.cardDensityEventCount` | 4 | §7.5 density 판정 기준 미명시 |
| `config.cardExposureOutdoorMinutes` | 90 | §7.5 exposure 판정 기준 미명시 |

세 엔드포인트는 각자 읽는 키만 검사하므로, RLS 키를 빼고 `evaluate`를 호출해도 `config_fallback`이
붙지 않습니다.

## 버전과 로깅

응답과 `/health`는 `calcVersion`을 반환합니다. 값은 `app/domain/plan_engine/version.py`에서 관리하며, 계산 규칙이나 근거 의미가 바뀌면 올립니다. Spring은 이 값을 계획 리비전과 함께 저장해 과거 계획의 재현성과 실험 단위 비교를 유지합니다.

개인화는 `modelVersion`을 따로 관리합니다(`app/domain/personalization_engine/version.py`, `/health`의 `personalizationModelVersion`). 보정 규칙·가드레일·사유 문장 의미가 바뀌면 올리고, Spring은 `USER_PREP_ESTIMATE.model_version`에 함께 저장합니다. 과거 보정을 소급 재계산하지 않습니다(D15).

웰니스는 `weightVersion`을 따로 관리합니다(`app/domain/wellness_engine/version.py`, `/health`의 `wellnessWeightVersion`). 가중치·정규화 경계·밴드·행동 매핑·승인 문구 의미가 바뀌면 올리고, Spring은 `PLAN_WELLNESS_SCORE.weight_version`에 저장합니다. 가중치를 바꿔도 과거 점수를 소급 재계산하지 않고 버전별로 분리 집계합니다(D15, §7.1).

로그에는 `request_id`, `calc_version`, `anchor_mode`, `feasible`, `prediction_confidence`, `degraded`, 준비 항목 개수, 소요 시간만 남깁니다. 개인화 로그는 `request_id`, `model_version`, `cause`, `knob`, `excluded`, `exclusion_reasons`, `degraded`, 소요 시간만 남깁니다 — `event_id`와 관측 타임스탬프는 기록하지 않습니다. 웰니스 로그는 `request_id`, `weight_version`, `wis_band`, 행동 개수, 예약 여부와 막힌 게이트, `degraded`만 남깁니다 — 원본 환경값과 렌더된 문장은 기록하지 않습니다. 일정 제목, 체크리스트 항목명, 위치, 토큰은 기록하지 않으며 예외 로그도 traceback 없이 예외 타입만 남깁니다. 요청자는 `X-Request-Id` 헤더로 추적 ID를 넘길 수 있고, 없으면 서버가 생성합니다.

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

M3에서 새로 생긴 결정 사항입니다.

- **`EnvironmentSnapshot` 확장 (김민형)** — M0 계약의 환경 스냅샷에는 강수확률·체감온도·기준시각만 있어 U와 P를 만들 수 없었습니다. ERD `PLAN_CONTEXT`에 이미 있는 `uv_index`·`pm10`·`pm25`를 Optional로 추가하고, 대기질은 등급(`airGrade`)으로 받습니다. 일교차 플래그용 `feelsLikeMin/MaxCelsius`도 추가했습니다. Plan Engine은 이 필드를 읽지 않으므로 계획 계산은 그대로입니다.
- **RLS·DWL 엔드포인트 신설** — M0 계약에는 WIS만 있었습니다. TRD §7.1이 RLS·DWL도 M3 산출물로 두고 있어 `POST /internal/v1/wellness/rush-load`와 `/daily-summary`를 추가했습니다. 기존 엔드포인트 스키마는 건드리지 않았습니다.
- **폭염·한파 경계값** — §7.2는 "폭염·한파 경계에서 1.0"이라고만 적었습니다. 기상청 주의보 기준을 따라 33℃ / −12℃를 제안값으로 넣었습니다. 원격 설정이라 배포 없이 바꿀 수 있습니다.
- **RLS 정규화 척도** — PRD §14.4는 "0~1로 정규화"까지만 정했습니다. 지연 30분에서 1.0, 극한 알림 2회에서 1.0을 제안값으로 넣었습니다.
- **마무리 카드 판정 기준** — §7.5는 시나리오 우선순위만 정했습니다. rushed RLS 70, density 4건, exposure 90분을 제안값으로 넣었습니다.
- **`actions`에는 외출 전 행동만** — 일정 중 행동은 `armedActionCode`로 분리했습니다. 같은 항목을 카드와 푸시에 두 번 세지 않기 위해서입니다.
- **관심사 보정 M은 계단 함수** — §7.2의 "최대 1.25"에 등급 규칙이 없어, 관련 관심 항목이 하나라도 있으면 최대값을 적용합니다. 변경 지점은 `normalize.interest_multiplier` 한 곳입니다.
- **행동 순위는 WIS 기여도 기준** — 강수는 §7.2가 T에 접어 넣었으므로 체감온도와 기여도를 공유하고, 동률이면 `uv > pm > temp > rain > hydration` 순입니다.
- **병합 키워드 표** — 골든 09의 "선크림 병합"을 만들려면 항목명과 행동을 잇는 표가 필요합니다. 모델 추론이 아니라 승인된 고정 목록(`templates.MERGE_KEYWORDS`)이며, 항목 추가는 콘텐츠 검토 대상입니다.

## Spring Backend 인계 주의사항

- Provider 경로의 초 단위 값을 AI 요청의 분 단위로 먼저 정규화합니다. 이동 시간을 과소평가하지 않도록 올림을 권장합니다.
- `Event` 원문 제목·본문·참석자·정확한 위치는 전송하지 않습니다.
- 기존 전역 Jackson `SNAKE_CASE` 설정은 변경하지 않고 AI 호출 전용 camelCase DTO를 사용합니다.
- 요청·응답 예시는 `examples/plan_compute_request.json`, `examples/plan_compute_response.json`입니다.
- 개인화 골든 케이스는 `tests/golden/personalization/*.json`에 입력·기대 출력이 함께 들어 있어 Java DTO 역직렬화 검증에 그대로 쓸 수 있습니다.
- 개인화 응답의 `previousValue`/`newValue`는 **`adjustedKnob`이 지정한 대상의 값**입니다. `prep_estimate`면 준비 시간 추정(분), `traffic_buffer`면 교통 버퍼(분)이고, `notification_lead`·`departure_lead`는 값이 `null`입니다.
- `excludedFromLearning=true`는 오류가 아닙니다. `EVENT_DELAY_REASON`·`USER_PREP_ESTIMATE`를 쓰지 말고 제외 사유만 지표로 집계하십시오.
- 웰니스 골든 케이스는 `tests/golden/wellness/{evaluate,rush_load,daily}/*.json`입니다.
- 웰니스 응답의 `wisScore=null`은 오류가 아닙니다. 환경 또는 경로가 없다는 뜻이고, 시간 계획은 그대로 진행해야 합니다.
- `dwlScore`는 저장하되 클라이언트에 표시하지 마십시오 (D5). `dwlBand`만 노출합니다.
- `cardMessage`는 `DAILY_WELLNESS_SUMMARY.card_message_snapshot`에 보존하십시오. 사후에 "어떤 문구가 실제로 나갔는지" 확인할 수 있어야 콘텐츠 검토가 성립합니다.
- `eventArmed=true`면 `armedActionCode` 1건만 `WELLNESS_EVENT_SCHEDULE`에 예약하고, `interval_minutes_snapshot`에 그 시점 사용자 설정을 복사하십시오.
- 대기질은 등급으로 보내십시오. 원값에서 등급을 유도하는 것은 엔진의 일이 아닙니다.
- OpenAPI 스키마는 `/openapi.json`에서 확인합니다.
- M1 API는 인증·DB·외부 API를 직접 처리하지 않습니다.

## 의존성 관리

로컬·CI 테스트는 `requirements.txt`, 컨테이너 이미지는 `pyproject.toml`을 사용합니다. 두 파일의 버전 일치는 `tests/test_dependency_contract.py`가 검증합니다.
