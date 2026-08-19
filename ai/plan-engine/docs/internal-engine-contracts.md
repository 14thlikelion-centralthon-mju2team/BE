# Ensom AI Engine 내부 계약 문서

> 문서 버전: m0-v1 (M2 추가 필드 반영)  
> 작성일: 2026-08-18 · 갱신: 2026-08-19  
> 작성자: 이지호 (AI·Algorithm·Data)  
> 인계 대상: 김민형 (BE), 박찬 (BE)

---

## 1. 개요

이 문서는 Spring Boot Backend와 Python AI 서버 사이의 데이터 계약을 정의한다.
M0에서 동결된 계약은 M1/M2/M3에서 스키마 변경 없이 각 엔진의 실제 로직만 구현할 수 있도록 설계되었다.

### 엔드포인트 목록

| Method | Path | 엔진 | 상태 |
|--------|------|------|------|
| `POST` | `/internal/v1/plans/compute` | Plan Engine | M1 구현 완료 |
| `POST` | `/internal/v1/personalization/adjust` | Personalization Engine | **M2 구현 완료** |
| `POST` | `/internal/v1/wellness/evaluate` | Wellness Engine | M0 stub |
| `GET` | `/health` | — | 상시 |

### 계약 버전

- Plan Engine: `calc_version` 필드 사용 (현재 `m1-plan-engine-1.0.0`)
- Personalization Engine: `contractVersion: "m0-v1"` · `modelVersion: "m2-personalization-1.0.0"`
- Wellness Engine: `contractVersion: "m0-v1"`

> M2는 계약 버전을 올리지 않았다. 추가된 필드가 전부 Optional 또는 기본값이라 §10의 non-breaking
> 규칙에 해당한다. 다만 **응답에 필드가 늘었으므로** Spring 측 역직렬화가 미지의 속성에서 실패하지
> 않아야 한다(`FAIL_ON_UNKNOWN_PROPERTIES=false`). 이 한 가지는 확인이 필요하다.

---

## 2. 공통 규칙

### 2.1 JSON 필드 표기

- Python 내부: `snake_case`
- JSON 요청/응답: **`camelCase`**
- Pydantic `alias_generator` + `serialize_by_alias=True`로 자동 변환

### 2.2 시간 형식

- **모든 datetime은 timezone-aware** (ISO-8601 offset)
- 허용: `2026-08-18T14:00:00+09:00`, `2026-08-18T05:00:00Z`
- 거부: `2026-08-18T14:00:00` (naive)
- `now` 필드는 Backend가 요청 시 주입 — AI 서버는 `datetime.now()` 사용 금지

### 2.3 Enum 직렬화

- 모든 enum은 `StrEnum` 기반, JSON에서는 문자열 값으로 직렬화
- 정의되지 않은 enum 값은 422 오류

### 2.4 Nullable vs 누락

- `Optional[T]` 필드: JSON에서 `null` 또는 키 생략 모두 허용
- 리스트 필드: 빈 배열 `[]`이 기본값 (`default_factory=list`)
- `extra="forbid"`: 정의되지 않은 필드가 있으면 422 오류

### 2.5 Config 전달 방식

- Backend의 `ENGINE_CONFIG` 테이블이 설정의 단일 기준
- 매 요청에 `config` 객체로 포함하여 전달
- AI 서버는 설정을 자체 저장하거나 DB에서 조회하지 않음

---

## 3. Plan Engine 계약

### 요청: `POST /internal/v1/plans/compute`

**Content-Type**: `application/json`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `now` | datetime | ✅ | 현재 시각 (Backend 주입) |
| `event` | EventSnapshot | ✅ | 일정 정보 |
| `prepEstimate` | PrepEstimate | ❌ | 사용자 준비 시간 추정 |
| `arrivalBufferMinutes` | int ≥ 0 | ✅ | 도착 여유 시간 |
| `trafficBufferMinutes` | int ≥ 0 | ✅ | 교통 버퍼 |
| `selectedRoute` | RouteSnapshot | ✅ | 선택된 경로 |
| `environment` | EnvironmentSnapshot | ❌ | 환경 데이터 |
| `prepItems` | PrepItemSnapshot[] | ❌ | 준비 항목 목록 |
| `config` | PlanEngineConfig | ✅ | 엔진 설정 |

### 응답 (200)

| 필드 | 타입 | 설명 |
|------|------|------|
| `prepStartAt` | datetime | 준비 시작 시각 |
| `recommendedDepartAt` | datetime | 권장 출발 시각 |
| `targetArriveAt` | datetime | 목표 도착 시각 |
| `breakdown` | PlanBreakdown | 시간 분해 |
| `reasons` | PlanReason[] | 계산 근거 |
| `checklist` | PlanChecklistItem[] | 체크리스트 |
| `feasible` | boolean | 실현 가능 여부 |
| `predictionConfidence` | enum | `high` / `mid` / `low` |
| `degraded` | string[] | 저하 사유 코드 |
| `calcVersion` | string | 계산 버전 |

> `feasible=false`는 오류가 아니라 **정상 응답**이다. Backend는 이를 사용자에게 안내해야 한다.

---

## 4. Personalization Engine 계약

### 요청: `POST /internal/v1/personalization/adjust`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `eventId` | string | ✅ | 대상 일정 ID |
| `planned` | PlannedExecutionSnapshot | ✅ | 계획된 실행 정보 |
| `actual` | ActualExecutionSnapshot | ✅ | 실제 실행 정보 |
| `outcome` | EventOutcome | ✅ | 결과 분류 |
| `currentEstimate` | CurrentPrepEstimate | ✅ | 현재 추정값 |
| `config` | PersonalizationEngineConfig | ✅ | 엔진 설정 |

`actual`의 모든 datetime 필드는 nullable — 센서 데이터가 없을 수 있다.

#### M2에서 추가된 입력 필드 (전부 Optional)

| 필드 | 타입 | 기본값 | 없으면 |
|------|------|--------|--------|
| `actual.actualPrepFinishedAt` | datetime? | null | `depart_late`를 판별할 수 없어 `prep_overrun`에 흡수되고 `degraded: ["prep_finish_unknown"]` |
| `actual.resultConfidence` | float? 0~1 | null | `resultSource='geo'`면 신뢰도 미달로 학습 제외 |
| `outcome.learningReverted` | bool | false | 되돌린 표본이 다시 학습된다 (§6.4 위반) |
| `outcome.eventModifiedAfterPlan` | bool | false | 무효한 계획 기준으로 학습한다 |
| `currentEstimate.seedMinutes` | float? | null | 상한 가드레일에 `config.seedFallbackMinutes`를 쓰고 `degraded: ["seed_fallback"]` |
| `currentEstimate.coldStartAdjusted` | bool | false | 콜드 스타트 1회 보정이 반복될 수 있다 |

> `actualPrepFinishedAt`는 ERD v3 `EVENT_EXECUTION`에 대응 컬럼이 없다. 타임스탬프 3개만으로는
> `Δdepart ≡ Δprep + Dactual − 계획창`이 항등식이어서 "준비가 길어짐"과 "준비는 끝났는데 안 나감"을
> 구분할 방법이 없다. TRD §6.2가 `adjustment_reason` 컬럼 추가를 권고한 것과 같은 성격의 **컬럼 추가
> 권고**다. 없어도 동작하며, 그 경우 엔진은 `depart_late`를 절대 반환하지 않는다.

### 응답 (200)

| 필드 | 타입 | 설명 |
|------|------|------|
| `cause` | DelayCause | 지연 원인 분류 |
| `adjustedKnob` | AdjustmentKnob | 조정 대상 |
| `previousValue` | float? | 조정 대상의 이전 값 |
| `newValue` | float? | 조정 대상의 새 값 |
| `adjustmentReason` | string? | 조정 사유 (승인 템플릿) |
| `excludedFromLearning` | boolean | 학습 제외 여부 |
| `modelVersion` | string | 모델 버전 |
| `contractVersion` | string | 계약 버전 |
| `causeConfidence` | float? 0~1 | 지배 원인의 신뢰도 → `EVENT_DELAY_REASON.confidence` (M2 추가) |
| `candidates` | CauseCandidate[] | 후보 원인 전체 `{cause, confidence, signalMinutes}` (M2 추가) |
| `exclusionReasons` | string[] | 학습 제외 사유 코드 (M2 추가) |
| `degraded` | string[] | 저하 사유 코드 (M2 추가) |

**`previousValue`/`newValue`는 `adjustedKnob`이 지정한 대상의 값이다.**

| `adjustedKnob` | 값의 의미 | 백엔드가 쓸 곳 |
|---|---|---|
| `prep_estimate` | 준비 시간 추정(분) | `USER_PREP_ESTIMATE.estimated_minutes` |
| `traffic_buffer` | 교통 버퍼(분) | 사용자별 교통 버퍼 |
| `notification_lead` | null | 알림 선행 시간 정책 (백엔드 소유) |
| `departure_lead` | null | 출발 알림 정책 (백엔드 소유) |
| `none` | 변화 없음 | 아무것도 쓰지 않는다 |

`candidates`는 `EVENT_DELAY_REASON`의 `(event_id, reason_code)` 복합 PK에 그대로 대응한다. 여러 행을
써도 되지만 **손잡이를 돌리는 것은 `adjustedKnob` 하나뿐**이다(TR-05).

`excludedFromLearning=true`는 오류가 아니다. `EVENT_DELAY_REASON`·`USER_PREP_ESTIMATE`를 쓰지 말고
`exclusionReasons`만 제외율 지표로 집계한다.

### DelayCause enum

| 값 | 의미 | 조정 대상 |
|----|------|-----------|
| `prep_late` | 준비 시작이 늦음 | `notification_lead` |
| `prep_overrun` | 준비 시간 초과 | `prep_estimate` (EMA) |
| `depart_late` | 출발이 늦음 | `departure_lead` |
| `traffic` | 교통 지연 | `traffic_buffer` |
| `external` | 외부 요인 (일정 변경) | 없음 · 학습 제외 |
| `unknown` | 판별 불가 또는 지연 없음 | 지연 없으면 `prep_estimate`, 부적격이면 없음 |

> `unknown`은 두 상황을 함께 쓴다. 표본이 부적격이면 `excludedFromLearning=true`이고,
> 지연 신호가 잡음 수준(`attributionMinSignalMinutes` 미만)이면 정상 관측이므로
> `excludedFromLearning=false`이면서 추정만 정련한다. 두 경우는 `excludedFromLearning`으로 구분한다.

### AdjustmentKnob enum

| 값 | 의미 |
|----|------|
| `prep_estimate` | 준비 시간 추정 |
| `notification_lead` | 알림 리드 타임 |
| `departure_lead` | 출발 리드 타임 |
| `traffic_buffer` | 교통 버퍼 |
| `none` | 조정 없음 |

### exclusionReasons 코드

`incomplete_timestamps` · `clock_skew` · `arrival_result_unknown` · `auto_manage_excluded` ·
`prep_duration_outlier` · `geo_confidence_low` · `event_modified` · `learning_reverted`

### degraded 코드

`seed_fallback` · `prep_finish_unknown` · `transit_unknown` · `cold_start_hold` · `step_limited` ·
`floor_clamped` · `ceiling_clamped` · `config_fallback`

---

## 5. Wellness Engine 계약

### 요청: `POST /internal/v1/wellness/evaluate`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `environment` | EnvironmentSnapshot | ❌ | 환경 데이터 |
| `estimatedOutdoorMinutes` | int? ≥ 0 | ❌ | 예상 야외 시간 |
| `userPreferences` | WellnessPreference[] | ❌ | 사용자 선호 |
| `existingPrepItems` | PrepItemSnapshot[] | ❌ | 기존 준비 항목 |
| `config` | WellnessEngineConfig | ✅ | 엔진 설정 |

### 응답 (200)

| 필드 | 타입 | 설명 |
|------|------|------|
| `wisScore` | int? (0~100) | WIS 점수 (알림 우선순위) |
| `wisBand` | WellnessBand? | `low` / `mid` / `high` |
| `normalizedLoads` | NormalizedWellnessLoads? | 정규화된 환경 부하 |
| `actions` | WellnessAction[] (최대 3개) | 행동 제안 |
| `eventArmed` | boolean | 이벤트 푸시 예약 여부 |
| `weightVersion` | string | 가중치 버전 |
| `contractVersion` | string | 계약 버전 |
| `degraded` | string[] | 저하 사유 코드 |

### 핵심 제약

- **actions 최대 3개** — 초과 시 422
- WIS는 건강 점수가 아니라 **알림 우선순위 값** (TRD 절대 원칙 3)
- 환경 데이터 null → 오류가 아니라 `degraded: ["env_unavailable"]`
- `is_sensitive=true` 항목은 엔진이 추천하지 않음
- 자유 생성 LLM 문장 반환 금지 — 승인된 `actionCode`만 사용

### WellnessTopic enum

`uv` / `pm` / `temp` / `rain` / `hydration`

### WellnessBand enum

`low` / `mid` / `high`

---

## 6. 원격 설정 키 매핑

### PlanEngineConfig

| Python 필드 | Backend ENGINE_CONFIG 키 | 기본값 |
|-------------|--------------------------|--------|
| `seed_fallback_minutes` | `seed_fallback_min` | 30 |
| `arrival_buffer_default_minutes` | `arrival_buffer_min` | 10 |
| `traffic_buffer_default_minutes` | `traffic_buffer_min` | 5 |
| `rain_threshold_percent` | `rain_threshold_pct` | 60 |
| `rain_extra_prep_minutes` | `rain_extra_prep_min` | 5 |
| `calc_version` | `plan_calc_version` | — |

### PersonalizationEngineConfig

| Python 필드 | Backend ENGINE_CONFIG 키 | 기본값 |
|-------------|--------------------------|--------|
| `prep_ema_alpha` | `prep_ema_alpha` | 0.30 |
| `late_weight` | `late_weight` | 1.50 |
| `early_weight` | `early_weight` | 0.70 |
| `max_step_minutes` | `max_step_min` | 15 |
| `cold_step_minutes` | `cold_step_min` | 20 |
| `prep_floor_minutes` | `prep_floor_min` | 10 |
| `prep_ceiling_ratio` | `prep_ceiling_ratio` | 2.0 |
| `model_version` | `personalization_model_version` | — |
| `seed_fallback_minutes` | `seed_fallback_min` | 30 |
| `cold_start_sample_threshold` | **미정** | 3 |
| `clock_skew_tolerance_seconds` | **미정** | 120 |
| `prep_outlier_max_minutes` | **미정** | 240 |
| `geo_min_confidence` | `auto_conf`(부록 A.3) 재사용 여부 미정 | 0.60 |
| `attribution_min_signal_minutes` | **미정** | 3 |

> 아래 5개는 TRD 본문에 값이 명시돼 있으나 부록 A 파라미터 레지스트리에 키가 없다.
> `engine_config` 행에 넣을 키 이름을 김민형과 확정해야 한다. 그때까지는 요청에서 생략해도
> 위 기본값으로 동작하며, `degraded: ["config_fallback"]`이 남는다.

### WellnessEngineConfig

| Python 필드 | Backend ENGINE_CONFIG 키 | 기본값 |
|-------------|--------------------------|--------|
| `wis_weight_uv` | `wis_w_uv` | 0.35 |
| `wis_weight_pm` | `wis_w_pm` | 0.25 |
| `wis_weight_temp` | `wis_w_temp` | 0.20 |
| `wis_weight_outdoor` | `wis_w_outdoor` | 0.20 |
| `interest_boost_max` | `interest_boost_max` | 1.25 |
| `outdoor_cap_minutes` | `outdoor_cap_min` | 120 |
| `wis_band_card` | `wis_band_card` | 40 |
| `wis_band_event` | `wis_band_event` | 70 |
| `weight_version` | `wellness_weight_version` | — |

> ⚠️ Backend에 `ENGINE_CONFIG` JPA 엔티티가 아직 없다면, 위 키 이름은 가안이다.  
> 실제 구현 시 김민형과 교차 확인 필요.

---

## 7. 오류 응답

### 형식

```json
{
  "detail": [
    {
      "type": "value_error",
      "loc": ["body", "planned", "prepStartAt"],
      "msg": "timezone-aware datetime is required",
      "input": "2026-08-18T12:00:00"
    }
  ]
}
```

### 오류 코드

| HTTP | 의미 |
|------|------|
| `200` | 정상 응답 (`feasible=false`도 200) |
| `422` | 입력 검증 실패 (타입, enum, timezone 등) |
| `500` | 내부 오류 (stack trace 미노출) |
| `501` | 엔진 미구현 (`STUB_MODE=false`일 때) |

---

## 8. Stub 활성화

| 엔드포인트 | STUB_MODE 영향 |
|---|---|
| `/internal/v1/plans/compute` | 없음 — 항상 실제 계산 (M1) |
| `/internal/v1/personalization/adjust` | **없음 — 항상 실제 계산 (M2)** |
| `/internal/v1/wellness/evaluate` | `true`(기본) stub 응답 · `false` 501 |

M2에서 개인화 엔드포인트의 `STUB_MODE` 게이트를 제거했다. 남은 stub은 웰니스뿐이므로,
Production에서 웰니스를 호출하지 않는다면 `STUB_MODE=false`로 두어 미구현 엔드포인트가
그럴듯한 가짜 응답을 내지 않게 하는 편이 안전하다.

---

## 9. Backend 타임아웃 권장값

| 엔진 | 권장 타임아웃 | 근거 |
|------|--------------|------|
| Plan | 2,000ms | 순수 계산, 현재 p99 < 10ms |
| Personalization | 2,000ms | EMA 계산 예상 |
| Wellness | 2,000ms | 점수 계산 + 행동 선택 |

타임아웃 초과 시 Backend는 계획 없이 일정 생성을 진행해야 한다 (TRD §5.2 원칙).

---

## 10. Breaking Change 규칙

다음 변경은 **breaking change**이며 양쪽 협의 후 버전을 올려야 한다:

- 필수 필드 추가
- 필드 타입 변경
- enum 값 제거 또는 이름 변경
- 응답 구조 변경
- 에러 코드 의미 변경

다음은 **non-breaking**이며 자유롭게 추가 가능:

- Optional 필드 추가
- enum 값 추가
- `degraded` 사유 코드 추가
- `tags` 추가

---

## 11. 실제 구현으로 전환하는 절차

M2가 개인화에서 이 절차를 밟았다. 웰니스(M3)도 같은 순서를 따른다.

1. 실제 엔진 로직 구현 (`app/domain/<engine>/`)
2. stub 엔드포인트 핸들러를 실제 엔진 호출로 교체하고 `STUB_MODE` 게이트 제거
3. **계약 모델은 변경하지 않는다** — 필요한 추가는 Optional/기본값만 (§10)
4. 골든 픽스처에 실제 `expected` 값 채우기 (`tests/golden/<engine>/`)
5. 속성 테스트로 §17.3 불변식 검증
6. `pytest` · `ruff check .` · `mypy app` 전체 통과 확인

### M2 구현 결과

| 항목 | 위치 |
|---|---|
| 도메인 계층 | `app/domain/personalization_engine/` (observation · eligibility · attribution · adjustment · reasons · engine) |
| 골든 케이스 10종 | `tests/golden/personalization/P01~P10.json` |
| 규칙별 단위 테스트 | `tests/test_personalization_engine.py` |
| 불변식 ①②③ 속성 테스트 | `tests/test_personalization_properties.py` |
| 알고리즘 상세 | `../README.md` "개인화 보정 규칙 (M2)" |

---

## 12. 참고 파일 위치

```
ai/plan-engine/
├── app/contracts/          ← 계약 모델 정의
├── app/domain/plan_engine/           ← M1 계산 로직
├── app/domain/personalization_engine/ ← M2 보정 로직
├── app/api/internal/       ← 개인화(실구현) · 웰니스(stub) 엔드포인트
├── app/testing/clock.py    ← 가상 시계
├── tests/contracts/        ← 계약 검증 테스트
├── tests/fixtures/         ← smoke fixture JSON
├── tests/golden/           ← 계획 골든 01~06
├── tests/golden/personalization/ ← 개인화 골든 P01~P10
└── examples/               ← 요청·응답 Mock JSON
```

---

## 13. 교차 검증 체크리스트 (김민형)

- [ ] Java DTO 필드명과 Python 모델 필드명 일치
- [ ] JSON camelCase 변환 규칙 동일
- [ ] enum 문자열 값 동일
- [ ] datetime offset 형식 동일
- [ ] null과 키 누락의 차이 처리 동일
- [ ] config key 매핑 확정 — 개인화 미정 5종 포함
- [ ] 계약 버전 합의
- [ ] 오류 코드 합의
- [ ] `feasible=false` 처리 방식 합의
- [ ] AI 서버 호출 타임아웃 합의
- [ ] stub 응답 역직렬화 테스트 통과

### M2 추가 확인 항목

- [ ] 응답의 미지 속성에서 Jackson이 실패하지 않는지 (`FAIL_ON_UNKNOWN_PROPERTIES=false`)
- [ ] `previousValue`/`newValue`를 `adjustedKnob`에 따라 다른 컬럼에 쓰는지
- [ ] `excludedFromLearning=true`일 때 아무것도 쓰지 않는지
- [ ] `EVENT_EXECUTION.actual_prep_finished_at` 컬럼 추가 여부 결정
- [ ] `USER_PREP_ESTIMATE.adjustment_reason` 컬럼 추가 여부 결정 (TRD §6.2 권고)
- [ ] `candidates`를 `EVENT_DELAY_REASON` 복수 행으로 저장할지 결정
- [ ] 되돌리기 API가 `outcome.learningReverted=true`로 재호출하는 경로 확보 (§6.4)
