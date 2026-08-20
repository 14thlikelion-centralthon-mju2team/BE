# Ensom ERD v3 — PRD v0.4.3 반영본

> 기준 문서: `Ensom_PRD_v0_4_3.pdf` (2026-08-16)
이전 버전: ERD v2 (피드백 P0/P1 반영본)
이번 개정 범위: 회원 전용 정책 · 웰니스 모델(WIS/RLS/DWL) · 맞춤 준비 항목 · 웰니스 이벤트 푸시 · 사후 요약
>
 
---

## 0. 이번 개정의 설계 축

기존 v2의 5개 시간축 분리 원칙(계획·행동·결과·평가·추정)을 유지하면서, **웰니스 레이어를 같은 원칙으로 추가**한다.

| 종류 | 시간 관리 | 웰니스 |
| --- | --- | --- |
| **계획** | `PLAN_REVISION` | `PLAN_WELLNESS_SCORE`, `PLAN_WELLNESS_ACTION` |
| **행동** | `EVENT_ACTION_LOG` | `PLAN_WELLNESS_ACTION.completion_status` |
| **결과** | `EVENT_EXECUTION` | `EVENT_EXECUTION.rush_load_score` (RLS) |
| **평가** | `EVENT_FEEDBACK` | `WELLNESS_EVENT_SCHEDULE.user_rating` |
| **추정** | `USER_PREP_ESTIMATE` | `DAILY_WELLNESS_SUMMARY` (DWL) |

**핵심 원칙**: WIS·RLS·DWL은 **의료 점수가 아니라 알림 우선순위 값**이므로(PRD 8.7, 14.3), 스키마에도 `score_purpose = 'priority_only'` 주석을 남기고 건강 데이터와 혼동되지 않게 분리한다.
 
---

## 1. 전체 ERD

```mermaid
erDiagram
    USERS ||--o{ USER_IDENTITY : authenticates
    USERS ||--|| USER_SETTING : has
    USERS ||--o{ USER_PERMISSION : grants
    USERS ||--o{ USER_CONSENT : agrees
    USERS ||--o{ USER_WELLNESS_PREF : configures
    USERS ||--o{ PUSH_DEVICE : registers
    USERS ||--o{ USER_PLACE : saves
    USERS ||--o{ CALENDAR_CONNECTION : connects
    USERS ||--o{ USER_PREP_RULE : defines
    USERS ||--o{ USER_PREP_ESTIMATE : learns
    USERS ||--o{ EVENT : owns
    USERS ||--o{ DAILY_WELLNESS_SUMMARY : receives
 
    CALENDAR_CONNECTION ||--o{ CALENDAR_SOURCE : contains
    CALENDAR_SOURCE |o--o{ EVENT : syncs
 
    EVENT ||--o{ PLAN_REVISION : generates
    EVENT ||--o{ EVENT_ACTION_LOG : records
    EVENT ||--o| EVENT_EXECUTION : results_in
    EVENT ||--o{ EVENT_DELAY_REASON : explains
    EVENT ||--o| EVENT_FEEDBACK : evaluated_by
    EVENT ||--o{ EVENT_PREP_ITEM : needs
    EVENT ||--o{ EVENT_CLASSIFICATION_REVIEW : reviewed_by
 
    PLAN_REVISION ||--o{ ROUTE_OPTION : compares
    PLAN_REVISION ||--o| PLAN_CONTEXT : snapshots
    PLAN_REVISION ||--o{ NOTIFICATION : schedules
    PLAN_REVISION ||--o{ PLAN_PREP_ITEM : applies
    PLAN_REVISION ||--o| PLAN_WELLNESS_SCORE : scores
    PLAN_REVISION ||--o{ PLAN_WELLNESS_ACTION : proposes
    PLAN_REVISION ||--o{ WELLNESS_EVENT_SCHEDULE : reminds
 
    USER_PREP_RULE |o--o{ PLAN_PREP_ITEM : source_of
    USER_PREP_RULE |o--o{ EVENT_PREP_ITEM : derived_into
    EVENT_PREP_ITEM |o--o{ PLAN_PREP_ITEM : source_of
 
    USER_WELLNESS_PREF |o--o{ PLAN_WELLNESS_ACTION : filters
    USER_WELLNESS_PREF |o--o{ WELLNESS_EVENT_SCHEDULE : governs
 
    USER_PLACE |o--o{ PLAN_REVISION : origin_of
    NOTIFICATION |o--o{ EVENT_ACTION_LOG : triggers
    NOTIFICATION |o--o| WELLNESS_EVENT_SCHEDULE : delivers
    PLAN_REVISION |o--o| EVENT_EXECUTION : final_plan_of
    EVENT_EXECUTION }o--o| DAILY_WELLNESS_SUMMARY : aggregated_into
 
    USERS {
        uuid user_id PK
        text email
        text nickname
        text timezone
        text account_status "active/withdrawn"
        timestamptz created_at
        timestamptz withdrawn_at
        timestamptz deleted_at
    }
 
    USER_IDENTITY {
        uuid identity_id PK
        uuid user_id FK
        text provider "google/apple/kakao"
        text provider_uid
        timestamptz linked_at
        timestamptz revoked_at
    }
 
    USER_SETTING {
        uuid user_id PK_FK
        int initial_prep_minutes "NULL 허용 · 자기보고 시드값"
        int arrival_buffer_minutes
        text notification_sensitivity
        bool personalization_enabled
        bool auto_manage_enabled
        bool wellness_event_enabled "웰니스 이벤트 푸시 전체 on/off"
        bool lockscreen_hide_sensitive
        timestamptz updated_at
    }
 
    USER_WELLNESS_PREF {
        uuid user_id PK_FK
        text wellness_topic PK "uv/pm/temp/rain/hydration"
        bool is_enabled
        int remind_interval_minutes "사용자 직접 설정 · 서비스가 판단하지 않음"
        int daily_event_cap "기본 1"
        timestamptz updated_at
    }
 
    USER_PLACE {
        uuid place_id PK
        uuid user_id FK
        text place_type
        text place_name
        text address
        double lat
        double lng
        bool is_primary
        timestamptz deleted_at
    }
 
    CALENDAR_CONNECTION {
        uuid calendar_connection_id PK
        uuid user_id FK
        text provider
        text external_account_id
        bytea refresh_token_enc
        timestamptz connected_at
        timestamptz revoked_at
    }
 
    CALENDAR_SOURCE {
        uuid calendar_source_id PK
        uuid calendar_connection_id FK
        text external_calendar_id
        text display_name
        bool is_writable
        bool is_default
        timestamptz deleted_at
    }
 
    EVENT {
        uuid event_id PK
        uuid user_id FK
        uuid calendar_source_id FK "NULL = 내부 생성"
        text external_event_id
        text source_type "internal/external/map_search"
        timestamptz starts_at
        timestamptz ends_at
        text location_state "required_resolved/required_missing/not_required/undecided"
        text destination_name
        double destination_lat
        double destination_lng
        text meeting_url
        text event_kind
        bool auto_manage_excluded
        text status
        timestamptz created_at
    }
 
    EVENT_CLASSIFICATION_REVIEW {
        uuid review_id PK
        uuid event_id FK
        text title_snapshot "분류 완료 후 즉시 NULL로 삭제, 로그성 원문 미보관"
        text question_type
        text suggested_value
        text user_answer
        text model_version
        numeric classification_confidence
        timestamptz asked_at
        timestamptz answered_at
        timestamptz title_purged_at "원문 삭제 시각"
    }
 
    PLAN_REVISION {
        uuid plan_id PK
        uuid event_id FK
        int revision_no
        uuid origin_place_id FK
        text origin_snapshot_name
        double origin_snapshot_lat
        double origin_snapshot_lng
        uuid selected_route_option_id FK
        timestamptz prep_start_at
        timestamptz recommended_depart_at
        timestamptz target_arrive_at
        int estimated_prep_minutes
        int extra_prep_minutes "환경 가산"
        int personal_routine_minutes "개인 루틴 가산"
        int travel_minutes
        int traffic_buffer_minutes
        text prediction_confidence
        text plan_status
        text calc_version
        timestamptz created_at
    }
 
    ROUTE_OPTION {
        uuid route_option_id PK
        uuid plan_id FK
        int route_rank
        text route_type "fastest/least_walk/least_transfer"
        int total_minutes
        int walk_minutes "야외 노출 추정의 핵심 입력"
        int transfer_count
        timestamptz depart_at
        timestamptz arrive_at
        jsonb route_payload
    }
 
    PLAN_CONTEXT {
        uuid plan_id PK_FK
        numeric temperature
        numeric feels_like
        numeric precipitation_prob
        int uv_index
        int pm10
        int pm25
        int traffic_delay_minutes
        int estimated_outdoor_minutes "도보 구간 합산 추정치"
        text weather_provider
        text air_provider
        text traffic_provider
        timestamptz observed_at
    }
 
    PLAN_WELLNESS_SCORE {
        uuid plan_id PK_FK
        numeric uv_load "U 0~1"
        numeric pm_load "P 0~1"
        numeric thermal_load "T 0~1"
        numeric outdoor_load "O 0~1 · 상한 120분"
        numeric interest_multiplier "M 1.0~1.25"
        int wis_score "0~100 · 알림 우선순위 전용"
        text wis_band "low/mid/high"
        text weight_version
        timestamptz calculated_at
    }
 
    PLAN_WELLNESS_ACTION {
        uuid wellness_action_id PK
        uuid plan_id FK
        text wellness_topic
        text action_code "sunscreen/mask/hydration/outerwear/umbrella"
        text action_label
        int display_rank "최대 3개"
        text reason_snapshot "제안 근거 · 설명가능성용"
        text completion_status "proposed/completed/dismissed"
        timestamptz responded_at
    }
 
    WELLNESS_EVENT_SCHEDULE {
        uuid wellness_event_id PK
        uuid plan_id FK
        uuid notification_id FK
        text action_code
        int interval_minutes_snapshot "사용자 설정값 스냅숏"
        timestamptz scheduled_at
        timestamptz sent_at
        text response_action "completed/snoozed/stop_today/ignored"
        text user_rating "useful/not_relevant/null"
        int sequence_no "동일 일정 내 회차 · 기본 1회 제한"
        timestamptz cancelled_at
        text cancel_reason "indoor/plan_changed/user_completed"
    }
 
    NOTIFICATION {
        uuid notification_id PK
        uuid plan_id FK
        text notification_category "time/wellness"
        text notification_type "relaxed/critical/disruption/wellness_event"
        timestamptz scheduled_at
        timestamptz sent_at
        text delivery_status
        text body_masked
        text trigger_reason "알림 로그 표시용"
    }
 
    USER_PREP_RULE {
        uuid prep_rule_id PK
        uuid user_id FK
        text rule_name
        text rule_category "supplement/medication/personal_item/routine/general_item"
        text action_type "carry/consume/purchase/timed_routine"
        text rule_timing "pre_departure/post_arrival"
        int default_minutes "timed_routine만 값 존재"
        text apply_event_kind
        text apply_time_band
        uuid apply_place_id
        text apply_weather
        bool is_required
        bool is_sensitive
        bool is_active
        timestamptz created_at
        timestamptz deleted_at
    }
 
    EVENT_PREP_ITEM {
        uuid event_prep_item_id PK
        uuid event_id FK
        uuid source_prep_rule_id FK
        text item_name
        text action_type
        int estimated_minutes
        bool is_required
        bool is_sensitive
        timestamptz created_at
    }
 
    PLAN_PREP_ITEM {
        uuid plan_prep_item_id PK
        uuid plan_id FK
        uuid prep_rule_id FK
        uuid event_prep_item_id FK
        text item_name_snapshot
        text action_type_snapshot
        int applied_minutes
        bool is_sensitive
        text source_type "rule/event_item/weather"
        text completion_status "pending/completed"
        timestamptz completed_at
    }
 
    EVENT_ACTION_LOG {
        uuid action_log_id PK
        uuid event_id FK
        uuid plan_id FK
        uuid notification_id FK
        text action_type "prep_started/snoozed/departed/item_checked/excluded"
        text action_source "user/geo/system"
        timestamptz action_at
        uuid client_event_id UK
    }
 
    EVENT_EXECUTION {
        uuid event_id PK_FK
        uuid final_plan_id FK
        timestamptz actual_prep_started_at
        timestamptz actual_departed_at
        timestamptz actual_arrived_at
        text arrival_result "early/on_time/rushed/late/unknown"
        text result_source "user/geo/inferred"
        int actual_outdoor_minutes
        numeric prep_delay_norm "Dp 0~1"
        numeric depart_delay_norm "Dd 0~1"
        numeric critical_alert_norm "E 0~1"
        int rush_load_score "RLS 0~100 · 운영 지표 전용"
        timestamptz created_at
        timestamptz updated_at
    }
 
    EVENT_DELAY_REASON {
        uuid event_id PK_FK
        text reason_code PK
        text reason_source "user/inferred"
        numeric confidence
        timestamptz created_at
    }
 
    EVENT_FEEDBACK {
        uuid event_id PK_FK
        text prep_timing_assessment "too_early/appropriate/too_late/unknown"
        text arrival_result
        text rush_assessment "사용자 촉박 평가"
        timestamptz created_at
    }
 
    DAILY_WELLNESS_SUMMARY {
        uuid summary_id PK
        uuid user_id FK
        date summary_date
        int event_count
        int total_outdoor_minutes
        numeric avg_wis_weighted "야외시간 가중 평균"
        numeric avg_rls
        int dwl_score "0~100"
        text dwl_band "low/mid/high"
        text card_scenario "default/exposure/density/rushed/stable"
        text card_message_snapshot
        bool is_viewed
        timestamptz created_at
    }
 
    USER_PREP_ESTIMATE {
        uuid estimate_id PK
        uuid user_id FK
        text scope_type "global/event_kind/weather/origin_place/time_band"
        text scope_value
        int estimated_minutes
        int sample_count
        numeric confidence
        text model_version
        timestamptz valid_from
        timestamptz valid_to
    }
 
    USER_PERMISSION {
        uuid user_id PK_FK
        text permission_type "calendar/location/notification/background_location"
        text status
        timestamptz updated_at
    }
 
    USER_CONSENT {
        uuid consent_event_id PK
        uuid user_id FK
        text consent_type "terms/privacy/location/marketing"
        text policy_version
        text action "agreed/revoked"
        bool is_required
        uuid idempotency_key UK
        timestamptz recorded_at
    }
 
    PUSH_DEVICE {
        uuid push_device_id PK
        uuid user_id FK
        uuid installation_id UK
        text current_token
        text token_status
        text platform
        timestamptz last_seen_at
        timestamptz revoked_at
    }
```
 
---

## 2. v2 → v3 변경 내역

### 신규 엔티티 (6개)

| 엔티티 | 근거 PRD 절 | 목적 |
| --- | --- | --- |
| `USER_IDENTITY` | 10.1, 11.1 | 회원 전용·소셜 로그인 전용 정책. 다중 제공자(Google/Apple/Kakao) 연결 |
| `USER_WELLNESS_PREF` | 10.5, 14.7 | 웰니스 관심 항목별 on/off와 **사용자 직접 설정 재알림 주기** |
| `PLAN_WELLNESS_SCORE` | 14.3, 16.4 | WIS 계산 결과와 입력 정규화값, 가중치 버전 보존 |
| `PLAN_WELLNESS_ACTION` | 12.6, 14.6 | 외출 전 제안 행동(최대 3개)과 완료·해제 응답 |
| `WELLNESS_EVENT_SCHEDULE` | 12.7, 16.5 | 선크림 재도포 등 일정 중 이벤트 푸시 예약·취소·응답 |
| `DAILY_WELLNESS_SUMMARY` | 10.6, 14.5, 16.8 | DWL 집계와 일일 마무리 카드 시나리오 |

### 확장된 엔티티

| 엔티티 | 추가 필드 | 근거 |
| --- | --- | --- |
| `USER_SETTING` | `wellness_event_enabled`, `lockscreen_hide_sensitive` | 8.8 사용자 통제 |
| `USER_PREP_RULE` | `rule_category`, `action_type`, `rule_timing` | 11.3 영양제·기호품목 4분류 / 귀가 후 루틴 |
| `PLAN_CONTEXT` | `uv_index`, `pm10`, `pm25`, `estimated_outdoor_minutes`, `air_provider` | 14.2 웰니스 입력 데이터 |
| `PLAN_REVISION` | `personal_routine_minutes` | 12.4 계산식에 개인 루틴 시간 분리 |
| `EVENT_EXECUTION` | `actual_outdoor_minutes`, RLS 3개 정규화값, `rush_load_score` | 14.4 촉박함 부담 점수 |
| `NOTIFICATION` | `notification_category`, `trigger_reason` | 13장 시간/웰니스 알림 분리, 10.2 알림 로그 |
| `EVENT_FEEDBACK` | `rush_assessment` | 24.3 사용자 촉박 평가 비율 |
| `PLAN_PREP_ITEM` | `completion_status`, `completed_at` | 24.4 맞춤 항목 체크 완료율 |
| `EVENT_EXECUTION.arrival_result` | `rushed` 값 추가 | 8.2 촉박 도착을 별도 상태로 |
| `USERS` | `account_status`, `withdrawn_at` | 11.5 탈퇴 정책 |

### 제거·축소

- `USERS.provider`, `USERS.provider_uid` → `USER_IDENTITY`로 이관 (다중 로그인 대응)
- 지도 관련 확장 없음 — PRD 17.2·18장에 따라 **환경 레이어·센서·자체 경로 점수용 엔티티는 만들지 않음**
- **`EVENT.title` 컬럼 삭제 확정.** 분류(장소 필요 여부·온라인 여부·일정 유형)는 일회성 판단이고 결과는 `location_state`·`event_kind`에 이미 저장되므로, 원문을 계속 보관할 이유가 없음. 제목이 필요한 순간(분류 시점)에만 `EVENT_CLASSIFICATION_REVIEW.title_snapshot`에 잠깐 담고, 분류 완료 즉시 NULL 처리한다.

---

## 3. 필수 DDL 제약

```sql
-- 회원 전용: 공급자별 식별자 유일
ALTER TABLE user_identity
  ADD CONSTRAINT uq_identity_provider UNIQUE (provider, provider_uid);
 
-- 웰니스 이벤트: 동일 일정·동일 행동 중복 방지 (13.4 기본 1회)
CREATE UNIQUE INDEX uq_wellness_event_once
  ON wellness_event_schedule (plan_id, action_code, sequence_no);
 
-- 외출 전 웰니스 행동 최대 3개 (14.3, WELL-03)
CREATE UNIQUE INDEX uq_wellness_action_rank
  ON plan_wellness_action (plan_id, display_rank);
ALTER TABLE plan_wellness_action
  ADD CONSTRAINT ck_wellness_rank CHECK (display_rank BETWEEN 1 AND 3);
 
-- WIS 밴드와 점수 정합성 (14.3 구간표)
ALTER TABLE plan_wellness_score ADD CONSTRAINT ck_wis_band CHECK (
  (wis_score BETWEEN 0 AND 39  AND wis_band = 'low')
  OR (wis_score BETWEEN 40 AND 69 AND wis_band = 'mid')
  OR (wis_score BETWEEN 70 AND 100 AND wis_band = 'high')
);
 
-- 일일 요약은 사용자·날짜당 1건
ALTER TABLE daily_wellness_summary
  ADD CONSTRAINT uq_daily_summary UNIQUE (user_id, summary_date);
 
-- 시간 소요 루틴만 minutes 값을 가짐 (11.3 처리 규칙)
ALTER TABLE user_prep_rule ADD CONSTRAINT ck_prep_minutes CHECK (
  (action_type = 'timed_routine' AND default_minutes IS NOT NULL)
  OR (action_type <> 'timed_routine' AND default_minutes IS NULL)
);
 
-- 웰니스 알림은 category가 wellness여야 함
ALTER TABLE notification ADD CONSTRAINT ck_noti_category CHECK (
  (notification_type = 'wellness_event' AND notification_category = 'wellness')
  OR (notification_type <> 'wellness_event' AND notification_category = 'time')
);
 
-- 일정 제목 원문 즉시 폐기 (분류 결과 저장 후 원문 삭제)
-- 애플리케이션 레벨: 분류 트랜잭션 커밋 시 아래를 같은 트랜잭션에서 실행
--   UPDATE event_classification_review
--   SET title_snapshot = NULL, title_purged_at = now()
--   WHERE review_id = :review_id;
ALTER TABLE event_classification_review
  ADD CONSTRAINT ck_title_purged CHECK (
    (title_snapshot IS NULL AND title_purged_at IS NOT NULL)
    OR (title_snapshot IS NOT NULL AND title_purged_at IS NULL AND answered_at IS NULL)
  );
 
-- 이하 v2에서 유지
CREATE UNIQUE INDEX uq_event_external
  ON event (calendar_source_id, external_event_id)
  WHERE calendar_source_id IS NOT NULL AND external_event_id IS NOT NULL;
 
CREATE UNIQUE INDEX uq_active_plan_per_event
  ON plan_revision (event_id) WHERE plan_status = 'active';
 
ALTER TABLE route_option
  ADD CONSTRAINT uq_route_in_plan UNIQUE (plan_id, route_option_id);
ALTER TABLE plan_revision
  ADD CONSTRAINT fk_selected_route
  FOREIGN KEY (plan_id, selected_route_option_id)
  REFERENCES route_option (plan_id, route_option_id);
```
 
---

## 4. 웰니스 데이터 흐름

```
[환경 수집]  PLAN_CONTEXT
             uv_index, pm10/25, feels_like, precipitation
             + estimated_outdoor_minutes (ROUTE_OPTION.walk_minutes에서 파생)
                    │
                    ▼
[점수 계산]  PLAN_WELLNESS_SCORE
             WIS = min(100, 100 × (0.35U + 0.25P + 0.20T + 0.20O) × M)
             M ← USER_WELLNESS_PREF에서 관심 항목 보정
             weight_version 기록 (16.9 설명가능성)
                    │
        ┌───────────┴───────────┐
        ▼                       ▼
   WIS 40~69                WIS 70~100
[외출 전 제안]            [+ 이벤트 푸시 후보]
PLAN_WELLNESS_ACTION      WELLNESS_EVENT_SCHEDULE
 최대 3개                  조건: 사용자 동의 + 노출 지속
 reason_snapshot 보존           + interval 도달 + 미완료
        │                       │
        ▼                       ▼
   completed/dismissed     completed/snoozed/stop_today
        └───────────┬───────────┘
                    ▼
[사후 집계]  EVENT_EXECUTION.rush_load_score (RLS)
             DAILY_WELLNESS_SUMMARY.dwl_score (DWL)
             DWL = 0.6 × WIS 야외시간 가중평균 + 0.4 × RLS 평균
                    │
                    ▼
             card_scenario 선택 → 일일 마무리 카드 (10.6)
```
 
---

## 5. 안전·개인정보 설계 반영

PRD 14.8·20.3의 의료 경계와 민감정보 원칙을 스키마 레벨에서 지킨다.

| 원칙 | 스키마 반영 |
| --- | --- |
| WIS는 건강 점수가 아님 (8.7) | `PLAN_WELLNESS_SCORE`에 피부·건강 관련 필드를 두지 않음. 점수는 `display_rank` 결정에만 사용 |
| 재도포 주기를 서비스가 판단하지 않음 (14.7) | `interval_minutes`는 `USER_WELLNESS_PREF`(사용자 입력)에만 존재. 스케줄에는 스냅숏만 저장 |
| 영양제·복용약은 효능 판단 안 함 (14.8) | `USER_PREP_RULE.rule_category`는 분류용일 뿐, 용량·성분·효능 필드 없음 |
| 잠금화면 마스킹 (11.3, 20.3) | `is_sensitive` → `NOTIFICATION.body_masked`에 일반화 문구 저장 |
| 하루 전체 경로 미저장 (16.7, 20.2) | 좌표는 `PLAN_REVISION.origin_snapshot_*`와 `EVENT.destination_*`만. 경로 폴리라인은 `route_payload`에 계획 단위로만 보관 |
| **일정 제목 미보관 (20.2, 확정)** | `EVENT.title` 컬럼 자체를 두지 않음. 분류 시 `EVENT_CLASSIFICATION_REVIEW.title_snapshot`에만 일시 저장, 분류 완료 트랜잭션 안에서 즉시 NULL 처리 |
| 탈퇴 시 삭제·익명화 (11.5, 20.5) | `USERS.account_status`, `withdrawn_at`으로 상태 관리. 하위 엔티티는 user_id CASCADE 또는 익명화 배치 |
 
---

## 6. 성공 지표와 스키마 매핑

PRD 24장 지표가 실제로 계산 가능한지 확인.

| 지표 | 계산 근거 |
| --- | --- |
| 웰니스 행동 완료율 | `PLAN_WELLNESS_ACTION` completed / proposed |
| 웰니스 이벤트 반응률 | `WELLNESS_EVENT_SCHEDULE` response_action ∈ (completed, snoozed) / sent |
| 웰니스 알림 적합률 | `WELLNESS_EVENT_SCHEDULE.user_rating = 'useful'` / rating 수집분 |
| 웰니스 커버리지 | WIS 생성된 일정 / `estimated_outdoor_minutes > 0` 일정 |
| 맞춤 준비 항목 체크 완료율 | `PLAN_PREP_ITEM` completed / 노출분 |
| 촉박 도착률 | `EVENT_EXECUTION.arrival_result = 'rushed'` |
| 극한 알림 발생 비율 | `NOTIFICATION.notification_type = 'critical'` |
| 일일 마무리 카드 확인률 | `DAILY_WELLNESS_SUMMARY.is_viewed` |
| 오늘은 그만 선택률 | `WELLNESS_EVENT_SCHEDULE.response_action = 'stop_today'` |
| 준비시간 화면 맞춤 항목 설정률 | `USER_PREP_RULE` 생성 시각 vs 온보딩 완료 시각 비교 |
 
---

## 7. 남은 결정 사항

PRD 부록 A.2 미결 사항과 연동해 스키마에 영향을 주는 항목만 정리.

1. **WIS 가중치 조정 시 과거 데이터 처리** — `weight_version`으로 버전 구분은 하되, 재계산할지 원본 유지할지 정책 필요
2. **DWL 노출 방식** — 숫자 노출 시 `dwl_score` 사용, 등급만 노출 시 `dwl_band`만 사용. 현재는 둘 다 저장 중
3. ~~`EVENT.title` 저장 여부~~ — **확정: 저장 안 함.** 분류 시점에만 `EVENT_CLASSIFICATION_REVIEW.title_snapshot`에 일시 보관 후 즉시 삭제. 단, 신뢰도 낮아 사용자에게 재확인을 물어야 하는 경우(`asked_at`~`answered_at` 사이) 짧게 유지되는 창이 생기므로, 이 구간의 보관 시간 상한(예: 24시간 미응답 시 자동 폐기)을 정책으로 추가 결정 필요
4. **웰니스 이벤트 일정당 최대 횟수** — 현재 `sequence_no`로 다회 확장 가능하게 열어뒀으나 기본 1회 제한(13.4). 상한값을 설정에서 조정 가능하게 할지 결정
5. **`EVENT_CLASSIFICATION_REVIEW` 이력 보존 범위** — v2에서 이월된 미결