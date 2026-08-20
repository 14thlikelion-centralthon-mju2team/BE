# Ensom API 명세서

**문서 버전:** v3.0
**작성일:** 2026-08-16
**근거 문서:** PRD v0.4.3 · ERD v3(웰니스 반영본) · TRD v2.0
**확정 스택:** Flutter · Spring Boot · PostgreSQL 16
**이 문서의 위치:** TRD v2.0 §12가 정의한 엔드포인트 목록과 공통 규약을 뼈대로, ERD v3의 테이블·필드명과 PRD v0.4.3의 요구사항 ID를 결합해 요청/응답 바디까지 구현 가능한 수준으로 채운 것이다. TRD §12를 대체하며, 세부 설계 근거(파이프라인, 상태 기계, 트랜잭션 경계 등)는 TRD 본문을 참조한다.
 
---

## 0. 이 문서가 참조하는 세 문서의 역할 분담

| 문서 | 이 API 명세에 기여한 것 |
|---|---|
| PRD v0.4.3 | 기능 요구사항 ID(AUTH/CAL/PLAN/MAP/NOTI/WELL/MODEL/REPORT/SET/DATA), 문구·의료 경계 원칙, 성공 지표 |
| ERD v3 | 테이블·컬럼명, 제약(UNIQUE/CHECK), 삭제·보존 정책 |
| TRD v2.0 | 엔드포인트 목록, 상태 기계, 멱등성/트랜잭션 규약, 파라미터 기본값(부록 A), 미결 결정(§20 D1~D10) |
 
---

## 1. 공통 규약

### 1.1 Base URL
```
https://api.ensom.app/v1
```
(서비스 도메인은 확정, 지도·IdP 공급자는 TRD D1·D2 미확정 — §16 참고)

### 1.2 인증
- `Authorization: Bearer <access_token>` — 전 엔드포인트 필수 (`/auth/*` 제외)
- access JWT 1시간, refresh 30일(기기별, `push_device`와 연결) — TRD §10.1
- 모든 자원은 토큰의 `user_id`로 행 수준 필터링. 타 사용자 자원 접근은 `404`(존재 여부 비노출) — PRD §23.4

### 1.3 공통 헤더
| 헤더 | 용도 |
|---|---|
| `Authorization` | Bearer 토큰 |
| `Idempotency-Key` | 모든 POST/PUT 필수. 24시간 내 동일 키 → 이전 응답 재생 (TRD §12.3) |
| `X-App-Version` | 최소 지원 버전 미만이면 `426` |
| `Accept-Language` | 알림·카드 문구 로컬라이즈 |

### 1.4 공통 응답 포맷

성공:
```json
{ "data": {}, "meta": { "requestId": "req_123", "serverTime": "2026-08-16T09:00:00+09:00" } }
```

실패:
```json
{ "error": { "code": "EVENT_NOT_FOUND", "retryable": false, "message": "string" } }
```

### 1.5 시간 표현 (TR-02)
- 저장은 전부 `timestamptz`(UTC), API 응답은 오프셋 포함 ISO-8601 필수
- 일정은 IANA 타임존 문자열(`Asia/Seoul`)을 함께 반환
- 종일 일정은 계획 대상에서 제외 (사용자가 시각을 지정하면 편입)

### 1.6 멱등성
쓰기 요청은 `Idempotency-Key` 필수. 행동 로그(`POST /plans/{id}/actions`)는 추가로 바디에 `clientEventId`(UUID)를 포함해야 하며, 서버는 `(user_id, client_event_id)` UNIQUE로 오프라인 재전송을 흡수한다 — 중복 요청은 오류가 아니라 `duplicated: true`를 담은 정상 응답이다 (TR-03).
 
---

## 2. 인증 (AUTH-01~04)

| Method | Path | 설명 |
|---|---|---|
| POST | `/auth/login` | 소셜 로그인 (IdP 미확정 — Google/Apple/Kakao 후보, TRD D2) |
| POST | `/auth/refresh` | 세션 갱신 |
| POST | `/auth/logout` | 로그아웃 |

**POST /auth/login**
```json
// Request
{ "provider": "google", "idToken": "string" }
 
// Response 200
{
  "data": {
    "accessToken": "jwt", "refreshToken": "opaque",
    "expiresIn": 3600,
    "user": { "userId": "uuid", "isNew": true }
  }
}
```
- 서버는 IdP 공개키로 검증 후 `user_identity (provider, provider_uid)` UNIQUE 기준 upsert
- 비회원/게스트 모드는 없다 — 로그인 실패 시 홈 등 핵심 화면 진입 자체가 차단된다 (PRD §10.1, AUTH-01)

> **iOS 심사 주의(TRD §10.1):** 타 소셜 로그인을 제공하면 Apple 로그인 병행이 심사 요건이므로, IdP를 하나만 고르더라도 iOS 출시 시 Apple은 사실상 필수.
 
---

## 3. 부트스트랩

| Method | Path | 설명 |
|---|---|---|
| GET | `/me/bootstrap` | 설정·파라미터·장소·맞춤 준비 항목·오늘 계획 일괄 조회 |

```json
{
  "data": {
    "settings": {},
    "permissions": [],
    "places": [],
    "prepItems": [],
    "todayPlan": {},
    "engineConfig": { "engineVer": "2.1.0", "wisVer": "w1" }
  }
}
```
앱 진입 시 이 한 번의 호출로 홈 화면 렌더에 필요한 데이터를 모은다 (TRD §12.1, TR-06 — `engineConfig`는 원격 설정값이라 배포 없이 바뀔 수 있음을 클라이언트가 인지하기 위함).
 
---

## 4. 사용자 설정 · 권한

| Method | Path | 설명 |
|---|---|---|
| GET / PATCH | `/me/settings` | 준비 시간 시드, 도착 여유, 알림 민감도, 웰니스 이벤트 전체 on/off, 잠금화면 숨김 |
| GET / PATCH | `/me/permissions` | 캘린더/위치/알림/모션 권한 상태 |
| DELETE | `/me` | 탈퇴 — 하드 삭제 (§14 참고) |

**PATCH /me/settings**
```json
{
  "initialPrepMinutes": 30,
  "arrivalBufferMinutes": 10,
  "notificationSensitivity": "normal",
  "wellnessEventEnabled": true,
  "lockscreenHideSensitive": true
}
```
- `initialPrepMinutes`는 낮은 신뢰도의 자기보고 시드값이며 계산에 그대로 쓰이지 않는다 (PLAN-01, ERD v3 `USER_SETTING.initial_prep_minutes`)

**권한 요청 시점 원칙 (PRD §11.4, TRD §14.2 — API 설계에 반영):** 클라이언트는 아래 순서로 개별 호출한다 — 로그인/약관(선행 조건) → 캘린더(연동 진입 시) → 위치 "사용 중"(첫 목적지 입력 시) → 알림(첫 계획 생성 후) → 위치 "항상"(자동 출발 확인 켤 때 별도 동의) → 웰니스 이벤트 알림(관심 항목 설정에서 별도 토글).
 
---

## 5. 장소 (SET-01)

| Method | Path | 설명 |
|---|---|---|
| GET / POST / PATCH / DELETE | `/places` `/places/{placeId}` | 집/학교/직장/기타 저장 |

좌표는 애플리케이션 레벨 AES-GCM 암호화 (TRD §14.3, 민감 정보).
 
---

## 6. 맞춤 준비 항목 (ONB-01, SET-02, PLAN-05)

| Method | Path | 설명 |
|---|---|---|
| GET / POST / PATCH / DELETE | `/prep-items` `/prep-items/{id}` | 반복 준비물·기호 품목·시간 루틴 CRUD |

**POST /prep-items**
```json
{
  "ruleName": "영양제",
  "ruleCategory": "supplement",
  "actionType": "consume",
  "ruleTiming": "pre_departure",
  "defaultMinutes": null,
  "isRequired": false,
  "isSensitive": false,
  "fromChip": true
}
```

| 필드 | 설명 |
|---|---|
| `ruleCategory` | `supplement`(영양제) \| `medication`(복용약) \| `personal_item`(개인 기호 품목) \| `routine`(시간 소요 루틴) \| `general_item` — PRD §11.3 구분 표, ERD v3 `USER_PREP_RULE.rule_category` |
| `actionType` | `carry`(챙기기) \| `consume`(사용·섭취하기) \| `purchase`(구매하기) \| `timed_routine`(시간이 필요한 루틴) — PRD §11.3, ERD v3 `USER_PREP_RULE.action_type` |
| `ruleTiming` | `pre_departure`(출발 전) \| `post_arrival`(도착 후) |
| `defaultMinutes` | `actionType: timed_routine`일 때만 값 허용, 그 외는 반드시 `null` (ERD v3 `ck_prep_minutes`) |
| `isSensitive` | true면 잠금화면·푸시에서 일반화된 문구("개인 준비")로 치환 — 항목별 커스텀 문구는 두지 않는다(PRD §11.3, TR-10) |
| `fromChip` | 추천 칩에서 선택했는지, 직접 입력했는지 — 요청 검증에만 쓰이고 영구 저장 여부는 미결(TRD D11) |

> **서버 검증 규칙(위험 12, PRD §1.1):** `fromChip: true` ∧ `isSensitive: true` 조합은 거부된다 — 담배·주류 등 민감·규제 품목은 추천 칩에 애초에 없으므로, 이 조합이 요청되면 클라이언트 오류로 간주해 `422 VALIDATION_ERROR`를 반환한다.
> **온보딩 통합(PRD §11.3):** 이 화면은 별도 온보딩 단계가 아니라 준비 시간 입력 화면 내부 섹션이므로, `PATCH /me/settings`(준비시간)와 `POST /prep-items`(맞춤 항목)는 같은 화면 제출 흐름에서 순차 호출되며 항목 등록은 선택 사항 — 건너뛰어도 온보딩 완료를 막지 않는다.
 
---

## 7. 캘린더 연동 (CAL-01, CAL-02)

| Method | Path | 설명 |
|---|---|---|
| GET / POST / DELETE | `/calendar/connections` | 연결 관리 |
| POST | `/calendar/sync` | 수동 동기화 |

- 읽기 전용, Google Calendar 우선 (TRD §11.3 — D2의 IdP와 OAuth 동의 통합 가능성)
- 5~15분 폴링 + 수동 동기화, `ext_uid`/`etag` 기준 증분 반영
- `attendees`·`description`은 파싱 단계에서 폐기 — 저장 컬럼 자체가 없다 (최소 수집 원칙)

---

## 8. 일정 (CAL-01, 03, 04, 05)

| Method | Path | 설명 |
|---|---|---|
| GET | `/events` `/events/next` | 목록 / 다음 일정 + 계획 요약 |
| POST | `/events` | 생성 (`source: map_search`이면 경로 정보 동봉) |
| PATCH / DELETE | `/events/{id}` | 수정 / 삭제 |
| POST | `/events/{id}/review` | 분류 확인 응답 |

**POST /events**
```json
{
  "title": "강남역 미팅",
  "startsAt": "2026-08-20T14:00:00+09:00",
  "endsAt": "2026-08-20T15:00:00+09:00",
  "placeNeed": "required_resolved",
  "destinationName": "강남역",
  "destinationLat": 37.498,
  "destinationLng": 127.027,
  "anchor": "arrive_by",
  "sourceType": "internal"
}
```
- `placeNeed`: `required_resolved` \| `required_missing` \| `not_required` \| `undecided` — 사용자 지정값이 항상 자동 분류보다 우선하며, 동기화가 이 필드를 덮어쓰지 않는다 (CAL-03, TRD §13.1 "사용자 지정 항상 우선")
- `anchor`: `depart_at`(지도 검색에서 출발 시각 기준 저장) \| `arrive_by`(도착 시각 기준, 기본값) — Plan Engine이 역산 방향을 결정하는 값 (TRD §5.3)

**POST /events/{id}/review**
```json
{ "questionType": "is_online", "userAnswer": "offline" }
```
- 신뢰도 낮은 분류(`classify_conf < 0.70`)일 때만 클라이언트가 이 확인 질문을 노출 (CAL-04, TRD 부록 A `CLASSIFY_MIN_CONF`)
- **일정 제목은 서버에 영구 저장되지 않는다.** 분류 시점에만 `EVENT_CLASSIFICATION_REVIEW.title_snapshot`에 잠깐 담기고, 분류 완료 트랜잭션 안에서 즉시 NULL 처리된다 (ERD v3 §2, 확정 사항)

---

## 9. 계획 (PLAN-01~05)

| Method | Path | 설명 |
|---|---|---|
| POST | `/events/{id}/plan/recalculate` | 강제 재계산 |
| GET | `/events/{id}/plans/latest` `/plans/{id}` | 계획 조회 (트레이스+체크리스트+degraded 포함) |
| PATCH | `/plans/{id}` | 사용자 직접 수정 |

**GET /plans/{id} 응답**
```json
{
  "data": {
    "revisionNo": 3, "engineVer": "2.1.0", "state": "NOTIFIED", "feasible": true,
    "prepStartAt": "2026-08-16T12:25:00+09:00",
    "departAt": "2026-08-16T13:10:00+09:00",
    "etaAt": "2026-08-16T13:50:00+09:00",
    "trace": [
      { "label": "개인 준비 시간", "minutes": 35, "source": "model", "adjusted": true,
        "reason": "최근 8회 기록 기준, 초기 설정보다 +5분" },
      { "label": "개인 루틴", "minutes": 10, "source": "prepItem", "adjusted": false,
        "reason": "렌즈·화장 (등록한 루틴)" },
      { "label": "이동 시간", "minutes": 42, "source": "provider", "adjusted": false,
        "reason": "외부 지도 API 기준" }
    ],
    "checklist": [
      { "label": "영양제", "origin": "user", "actionType": "consume", "state": "pending", "private": false },
      { "label": "선크림", "origin": "user", "actionType": "carry", "state": "pending",
        "reason": "자외선 높음 · 야외 45분" },
      { "label": "물·텀블러", "origin": "wellness", "actionType": "carry", "state": "pending",
        "reason": "체감온도 높음" }
    ],
    "wellness": { "wis": 72, "wisVer": "w1", "actionsShown": 2, "eventArmed": true },
    "degraded": []
  }
}
```
- `trace`, `checklist`는 **서버가 정렬하지 않는다** — 화면 순서는 PRD §31 미결 사항(웰니스 카드 우선순위)이므로 클라이언트가 결정, 확정되면 앱만 바꾸면 된다 (TRD §12.2)
- `checklist`의 `private: true` 항목은 잠금화면·푸시에서 `lockAlias`로 치환된다 (TR-10)
- `degraded`는 외부 API 실패 등으로 저하된 계산 항목 목록 — 빈 배열이면 정상 (PRD §23.2 "일부 실패해도 앱 중단 없음"의 API 표현)

**계산 파이프라인 (참고, TRD §5.3):**
```
목표 도착 = event.startsAt − arrivalBuffer
권장 출발 = 목표 도착 − route.totalSec − trafficBuffer
준비 시작 = 권장 출발 − prepMinutes − 상황별 추가(강수 등) − Σ routine 항목 extraMin
```
`anchor: depart_at`인 일정(지도 검색 저장)은 권장 출발을 고정하고 목표 도착을 정방향으로 계산한다.

**체크리스트 병합 규칙 (PLAN-05, TRD §5.4):** 사용자 등록 항목과 웰니스 제안이 같은 대상(예: 둘 다 "선크림")이면 사용자 항목 1개로 합치고 근거만 웰니스 쪽을 붙인다. 노출 상한은 맞춤 항목 3개 + 웰니스 3개.
 
---

## 10. 경로 (MAP-01~04)

| Method | Path | 설명 |
|---|---|---|
| GET | `/plans/{id}/routes` | 경로 후보 3종 |
| POST | `/plans/{id}/routes/select` | 선택 (재계산 동반) |

```json
// GET /plans/{id}/routes 응답
{
  "data": [
    { "routeId": "uuid", "rank": "fastest", "totalSec": 2520, "walkSec": 660, "transfers": 1, "outdoorSec": 480 },
    { "routeId": "uuid", "rank": "least_walk", "totalSec": 2820, "walkSec": 240, "transfers": 2, "outdoorSec": 200 },
    { "routeId": "uuid", "rank": "least_transfer", "totalSec": 3060, "walkSec": 540, "transfers": 0, "outdoorSec": 420 }
  ]
}
```
- MVP는 이 3종(가장 빠른/도보가 적은/환승이 적은)만 제공, 고급 시각화·레이어는 응답에 없다 (MAP-04, 미니멀 UI)
- `outdoorSec`(도보 구간 합)이 **웰니스 엔진의 핵심 입력**이다 (§12 참고) — 지하 환승 구간을 야외로 계산하면 WIS가 과대평가되므로, 지도 공급자가 leg 유형(지하/지상)을 구분하는지가 공급자 선정 기준에 포함된다 (TRD D1)

---

## 11. 알림 (NOTI-01~05)

| Method | Path | 설명 |
|---|---|---|
| GET | `/notifications/today` | 당일 알림 로그 (시간+웰니스 통합) |

```json
{
  "data": [
    { "notificationId": "uuid", "class": "time", "slot": "A", "sentAt": "...",
      "message": "20분 뒤 준비를 시작할 예정입니다.", "reaction": "prep_started" },
    { "notificationId": "uuid", "class": "wellness", "slot": "W", "sentAt": "...",
      "message": "야외 이동이 계속되고 있어요. 설정한 시간이 지났다면 선크림을 다시 확인해 보세요.",
      "reaction": "completed" }
  ]
}
```

**알림 예산 (TRD §8.1, §8.3):**
- 시간 알림(여유/극한/돌발): 일정당 기본 3회, 상태 입력 시 남은 슬롯 소각
- 웰니스 이벤트: 별도 W 슬롯, 일정당 기본 1회, 시간 예산과 독립
- 재계산 결과가 출발 시각을 5분 이상 바꿀 때만 돌발 슬롯 사용(`Δ ≥ 5분`), 2~5분은 로그만, 2분 미만은 리비전조차 만들지 않음
- 자동 보정으로 준비 시각이 당겨진 사실 자체는 푸시하지 않는다 — 홈·로그에서만 확인 가능

**멱등성:** `dedup_key = sha1(event_id:slot:revision_no)`로 DB UNIQUE 제약을 걸어 중복 발송을 구조적으로 차단한다 (TRD §8.4, §13.1).

**클라이언트 로컬 알림 이중화(TR-07):** 준비 시작·출발 임박 알림은 계획 확정 시 클라이언트가 로컬 알림 2건으로 미리 예약하고, 서버 푸시가 먼저 도착하면 로컬을 취소한다 — FCM은 전송 시각을 보장하지 않기 때문. API 레벨에서는 `POST /plans/{id}/notifications/local-ack` 같은 별도 엔드포인트가 필요할지, 계획 응답의 `prepStartAt`/`departAt`만으로 클라이언트가 로컬 스케줄링을 자체 처리할지는 미결(§16-1).
 
---

## 12. 웰니스 (WELL-01~06)

### 12.1 점수 조회 (계획 응답에 포함, §9 참고)

`GET /plans/{id}` 응답의 `wellness` 필드가 WIS 요약을, `checklist`의 `origin: wellness` 항목이 제안된 행동을 담는다. 별도 점수 전용 엔드포인트는 두지 않는다 — 점수는 항상 계획과 함께 조회된다.

**WIS 계산식 (PRD §14.3, TRD §7.1):**
```
WIS = min(100, 100 × (0.35·U + 0.25·P + 0.20·T + 0.20·O) × M)
```
U(자외선)/P(미세먼지)/T(체감온도·강수)/O(야외노출, 상한 120분) 각 0~1 정규화, M(관심항목 보정) 1.0~1.25.

| WIS 구간 | 동작 |
|---|---|
| 0~39 | 일정 상세에만 표시, 푸시 없음 |
| 40~69 | 외출 전 준비 카드에 행동 1~2개 |
| 70~100 | 행동 제안 + 동의 시 웰니스 이벤트 알림 후보 생성 |

WIS는 **의료 위험도나 피부 상태 점수가 아니라 알림 우선순위 값**이다 (PRD §8.7, ERD v3 `score_purpose = 'priority_only'` 원칙).

### 12.2 웰니스 행동 응답 기록 (WELL-03)

| Method | Path | 설명 |
|---|---|---|
| POST | `/plans/{id}/checklist/{itemId}/resolve` | 완료/해제 |

```json
{ "state": "completed" }
```

### 12.3 웰니스 이벤트 스케줄 (선크림 재도포 등, WELL-04, NOTI-04)

별도 조회 API는 없고 §11의 알림 로그에 `class: wellness`로 통합 노출된다. 응답 액션은 알림 자체의 액션 버튼으로 처리:

| Method | Path | 설명 |
|---|---|---|
| POST | `/notifications/{id}/respond` | 완료 / 30분 뒤 / 오늘은 그만 |

```json
{ "action": "completed" }  // completed | snoozed_30m | stop_today
```

**발사 조건 4중 게이트 (TR-11, 전부 AND):**
1. 사용자가 해당 항목과 이벤트 알림을 켬 (`USER_WELLNESS_PREF.is_enabled`)
2. WIS ≥ 70 (`WELLNESS_EVENT_MIN`)
3. 야외 노출이 계속 진행 중
4. 사용자가 설정한 재알림 주기 도달 (`interval_minutes` — **서비스가 정하지 않고 사용자가 직접 설정**, PRD §14.7)
5. 같은 일정·같은 행동에 완료/오늘그만이 없음

- 일정당 기본 1회 제한, `dedup_key = sha1(event:W:action:revision)`
- '오늘은 그만' → 당일 해당 행동 전체 중단. 연속 2회 무시 → 해당 항목 빈도 1단계 하향

> **의료·소비 판단 경계(TR-04, TR-09):** 서버는 SPF·피부 타입·제품 성능·복용량·효능을 절대 판단하지 않는다. 알림 문구는 사전 승인된 템플릿(PRD 부록 B.4)에서만 나오며, 자유 생성 LLM은 이 경로에 쓰지 않는다.

### 12.4 웰니스 관심 항목 설정 (WELL-06)

| Method | Path | 설명 |
|---|---|---|
| GET / PATCH | `/me/wellness-prefs` | 자외선/미세먼지/기온/강수/수분 항목별 on/off + 재알림 주기 |

```json
{
  "prefs": [
    { "topic": "uv", "isEnabled": true, "remindIntervalMinutes": 120, "dailyEventCap": 1 }
  ]
}
```

### 12.5 일일 마무리 카드 (WELL-05)

| Method | Path | 설명 |
|---|---|---|
| GET | `/summary/daily?date=` | 하루 요약 카드 |

```json
{
  "data": {
    "summaryDate": "2026-08-16",
    "eventCount": 3,
    "totalOutdoorMinutes": 43,
    "dwlBand": "mid",
    "cardScenario": "exposure",
    "message": "자외선이 높은 시간대의 예상 야외 이동이 길었어요. 지금은 수분을 보충하고 편안하게 쉬어주세요.",
    "isViewed": false
  }
}
```
- `dwlBand`(낮음/보통/높음)만 필수 노출, 숫자(`dwl_score`) 노출 여부는 PRD §31 미결(TRD D5) — 응답 필드는 준비해두되 클라이언트가 숫자를 보여줄지는 별도 결정 대기
- 관리 일정 0건이면 카드 자체를 생성하지 않음(`404`), 숫자를 지어내지 않는다
- 개인 특이 항목·복용약은 요약 생성 입력에서 원천 배제 (PRD §14.8)

---

## 13. 상태·행동 기록 (REPORT-01, 02)

| Method | Path | 설명 |
|---|---|---|
| POST | `/plans/{id}/actions` | 행동 이벤트 배치 기록 (오프라인 큐의 최종 도착지) |

```json
// Request
{
  "type": "prep_started",
  "deviceTs": "2026-08-16T12:31:00+09:00",
  "source": "manual",
  "clientEventId": "client-uuid"
}
// type: prep_started | preparing | departed | arrived | snoozed | skipped
//     | plan_edited | checklist_done | wellness_done | wellness_later | wellness_stop
// source: manual | notification_action | geofence
 
// Response — 갱신된 계획을 함께 반환
{
  "data": {
    "accepted": true,
    "duplicated": false,
    "plan": { "state": "PREPARING", "...": "..." }
  }
}
```
- `clientEventId`가 이미 처리된 값이면 `duplicated: true`와 함께 동일 응답을 재생 (TR-03) — 오프라인 재전송이 개인화 모델을 오염시키지 않도록 하는 핵심 안전장치
- 지오펜스 판정도 이 엔드포인트로 들어온다(`source: geofence`) — 좌표는 전송하지 않고 판정 결과(`type`, `confidence`)만 전송 (§14 프라이버시 원칙)
- 서버는 `device_ts`와 수신 시각 차이가 ±120초를 넘으면 `clock_skew` 플래그를 붙여 해당 표본을 개인화 학습에서 제외한다

---

## 14. 개인화 (MODEL-01, 02)

| Method | Path | 설명 |
|---|---|---|
| POST | `/plans/{id}/personalization/revert` | 직전 보정 되돌리기 + 해당 표본 영구 제외 |
| DELETE | `/personalization` | 개인화 초기화 (행동 로그는 유지) |

**원인 분리 보정 (MODEL-01, TR-05 핵심 원칙):** 준비 시작 지연·실제 준비 초과·출발 지연·교통 지연 각각의 관측은 **정확히 하나의 손잡이만** 조정한다 — 교통 지연으로 늦은 날에도 "개인 준비 시간"이 늘어나는 오귀속을 막기 위함. API 소비자(프론트) 입장에서는 계획 응답의 `trace[].source`(`model`/`prepItem`/`provider` 등)로 어떤 값이 어떤 근거로 조정됐는지 항상 구분해서 받는다.

```
P_new = (1-0.3)·P_old + 0.3·D_actual
outcome ∈ {late, tight} → α ×1.5 (실패가 더 강한 신호)
outcome = too_early → α ×0.7 (줄이는 방향은 신중히)
가드레일: P ∈ [10분, seed×2], 1회 변화 ≤ 15분
```
 
---

## 15. 데이터 삭제·계정 수명주기 (DATA-01, 02, AUTH-04)

| 전이 | API | 처리 |
|---|---|---|
| 로그아웃 | `POST /auth/logout` | 서버는 refresh 폐기, 데이터 유지. 클라이언트가 토큰·민감 캐시·예약 로컬 알림 소거 |
| 개인화 초기화 | `DELETE /personalization` | 준비시간 모델·알림 시점·교통 버퍼·웰니스 선호 초기화, 행동 로그는 유지 |
| 탈퇴 | `DELETE /me` | 동기 하드 삭제(CASCADE) — 캘린더 연결·푸시 토큰 즉시 폐기, 동의 이력만 법정 보존. 삭제 항목을 응답에 명시 |

```json
// DELETE /me 응답
{
  "data": {
    "deleted": ["events", "plans", "prepItems", "places", "calendarConnections", "pushDevices"],
    "retained": ["consentHistory"]
  }
}
```
재가입 시 이전 개인화 데이터는 복구하지 않는다 (PRD §11.5).
 
---

## 16. 데이터 모델 요약

| 리소스 | 근거 ERD 테이블 | 핵심 필드 |
|---|---|---|
| Event | `event` | startsAt, placeNeed, destination, anchor, sourceType, status |
| Plan | `plan_revision` + `plan_context` + `plan_wellness_score` | revisionNo, prepStartAt/departAt/etaAt, feasible, trace(jsonb), env(jsonb) — wisScore는 `plan_wellness_score`가 별도 테이블 |
| RouteOption | `route_option` | rank, totalSec, walkSec, outdoorSec |
| PrepItem | `user_prep_rule` | ruleCategory, actionType, defaultMinutes, isSensitive, fromChip(저장 여부 TRD D11 미정) |
| ChecklistItem | `plan_prep_item` + `plan_wellness_action` | origin(user→plan_prep_item / wellness→plan_wellness_action), state — 두 테이블을 API가 병합해 응답, 단일 테이블 아님 |
| Notification | `notification` | class(time/wellness), dedupKey — **ERD v3에 dedup_key 컬럼 없음, §18-6 참고** |
| ActionLog | `event_action_log` | type, source, clientEventId(UNIQUE) |
| DailyWellnessSummary | `daily_wellness_summary` | dwlBand, cardScenario |
| PrepEstimate | `user_prep_estimate` | estimatedMinutes, sampleCount, confidence, modelVersion |
 
---

## 17. 공통 에러 코드

`INVALID_ARGUMENT` · `UNAUTHORIZED` · `FORBIDDEN` · `NOT_FOUND` · `CONFLICT` · `RATE_LIMITED` · `INTERNAL_ERROR`
`EVENT_CLASSIFICATION_UNCERTAIN` · `PLAN_INFEASIBLE` · `ROUTE_PROVIDER_UNAVAILABLE` · `WELLNESS_DATA_UNAVAILABLE` · `PERMISSION_REQUIRED` · `SENSITIVE_CHIP_REJECTED`(민감 항목을 추천 칩으로 등록 시도)
 
---

## 18. 미결 사항

세 근거 문서에 이미 명시된 미결(TRD §20 D1~D10)은 이 API 설계에도 그대로 영향을 준다. 이 문서에서 새로 드러난 것만 추가한다.

1. **로컬 알림과 서버 알림의 API 경계** — TR-07의 클라이언트 로컬 이중화가 서버 API 호출을 필요로 하는지(예: ack 엔드포인트), 아니면 계획 응답 필드만으로 클라이언트가 자체 처리하는지 확정 필요.
2. **지도 공급자 미확정(D1)** — `RouteOption.rank`, `outdoorSec`의 실제 응답 형태는 공급자가 leg 유형(지하/지상)을 구분해 제공하는지에 따라 달라질 수 있음.
3. **소셜 로그인 IdP 미확정(D2)** — `/auth/login`의 `provider` enum 값 확정 필요.
4. **WIS 가중치·구간, DWL 표시 방식(D3, D5)** — 이 문서의 계산식·응답 필드는 PRD 초기 휴리스틱 그대로이며, 베타 A/B 결과로 값만 바뀌고 API 계약(필드명)은 원격 설정(`engine_ver`/`wis_ver`)으로 흡수되도록 이미 설계됨(TR-06) — 재작성 불필요.
5. **선크림 이벤트 기본 제공 여부·최대 횟수(D4)** — 현재 API는 사용자 켬 상태를 전제로 설계, 기본값이 off로 바뀌어도 API 계약은 동일.
6. **`notification.dedup_key` 컬럼이 ERD v3에 없음** — §11·§12.3이 요구하는 `dedup_key = sha1(...)` UNIQUE 제약을 걸 컬럼이 ERD v3 `NOTIFICATION`에는 없다(구 TRD 초안에는 있었음). 스키마 소유자 확인 필요 — MIGRATION.md 참고.
7. **PUSH_DEVICE·USER_CONSENT·CALENDAR_SOURCE·EVENT_FEEDBACK·EVENT_DELAY_REASON에 대응하는 엔드포인트가 이 문서에 없음** — ERD v3에는 있는 엔티티인데 API 표면이 비어 있다. 각각 실제로 별도 엔드포인트가 필요한지, 다른 엔드포인트에 묻어가는지 확인 필요 — MIGRATION.md 참고.

---

## 19. 다음 액션

- §18-1(로컬/서버 알림 API 경계)은 M2(TRD 마일스톤) 착수 전 프론트와 확정 권장
- 지도·IdP 공급자 확정(D1·D2) 시 §10, §2의 provider enum만 갱신하면 나머지 계약은 유지됨