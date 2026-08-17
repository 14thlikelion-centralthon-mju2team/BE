# Ensom M1 Plan Engine

기존 Java Spring Boot 백엔드와 독립적으로 실행되는 결정론적 Python 계획 엔진입니다.

## 책임 범위

- 정규화된 일정·준비시간·경로·환경 입력으로 준비 시작, 권장 출발, 목표 도착 시각 계산
- `timed_routine` 준비 항목만 준비시간에 합산
- 강수 조건에 따른 준비시간 가산 및 우산 체크리스트 병합
- 계산 근거, 실행 가능 여부, 입력 완전성 confidence 반환

DB, 외부 지도·환경 API, 인증, FCM, 알림 스케줄링, 일정 원문 저장은 담당하지 않습니다.

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
app/domain/plan_engine/   순수 계산 계층 (models, engine, checklist, constraints, reasons, confidence)
app/schemas/plan.py       도메인 모델을 API용으로 재노출
app/api/routes/plan.py    HTTP, 요청 식별자, 로깅
```

도메인 계층에는 FastAPI, DB, HTTP 클라이언트, `datetime.now()`, 환경변수 조회가 없습니다. 현재 시각과 모든 설정은 `PlanInput`으로 주입합니다.

## 내부 API

```text
GET  /health
POST /internal/v1/plans/compute
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

## 버전과 로깅

응답과 `/health`는 `calcVersion`을 반환합니다. 값은 `app/domain/plan_engine/version.py`에서 관리하며, 계산 규칙이나 근거 의미가 바뀌면 올립니다. Spring은 이 값을 계획 리비전과 함께 저장해 과거 계획의 재현성과 실험 단위 비교를 유지합니다.

로그에는 `request_id`, `calc_version`, `anchor_mode`, `feasible`, `prediction_confidence`, `degraded`, 준비 항목 개수, 소요 시간만 남깁니다. 일정 제목, 체크리스트 항목명, 위치, 토큰은 기록하지 않으며 예외 로그도 traceback 없이 예외 타입만 남깁니다. 요청자는 `X-Request-Id` 헤더로 추적 ID를 넘길 수 있고, 없으면 서버가 생성합니다.

## 팀 확인이 필요한 결정

- `predictionConfidence`: 프롬프트 §15는 준비시간 누락을 MID와 LOW에 동시에 두어 모순됩니다. 현재는 준비시간 누락 또는 오래된 경로를 LOW로 처리하며, 변경 지점은 `confidence.py` 한 곳입니다.
- 작업 브랜치: 프롬프트는 `feat/ai-plan-engine`(기준 `develop`)이지만 저장소 기본 브랜치는 `dev`입니다.
- `/internal/v1/plans/compute`에는 인증이 없습니다. 배포 시 내부 네트워크로만 노출하거나 서비스 토큰을 도입할지 결정이 필요합니다.

## Spring Backend 인계 주의사항

- Provider 경로의 초 단위 값을 AI 요청의 분 단위로 먼저 정규화합니다. 이동 시간을 과소평가하지 않도록 올림을 권장합니다.
- `Event` 원문 제목·본문·참석자·정확한 위치는 전송하지 않습니다.
- 기존 전역 Jackson `SNAKE_CASE` 설정은 변경하지 않고 AI 호출 전용 camelCase DTO를 사용합니다.
- 요청·응답 예시는 `examples/plan_compute_request.json`, `examples/plan_compute_response.json`입니다.
- OpenAPI 스키마는 `/openapi.json`에서 확인합니다.
- M1 API는 인증·DB·외부 API를 직접 처리하지 않습니다.

## 의존성 관리

로컬·CI 테스트는 `requirements.txt`, 컨테이너 이미지는 `pyproject.toml`을 사용합니다. 두 파일의 버전 일치는 `tests/test_dependency_contract.py`가 검증합니다.
