# Vium → Ensom 전환 계획

> 작성일 2026-08-16 · 작성자 박찬(백엔드A) · 브랜치 `setting/ensom-reset` (base `dev` @ 8e630c5)
> 근거 문서: PRD v0.4.3 · TRD v2.0 · ERD v3 · API 명세서 · MILESTONE — **충돌 시 PRD가 기준**
>
> **개정 이력**
> 1차 — TRD §3이 "Python 3.12 + FastAPI"로 적혀 있어 전면 재작성으로 판단했으나, 팀 확인 결과
> **백엔드는 기존 Java 21 + Spring Boot 구조를 그대로 재사용**하기로 확정. 문서(TRD/MILESTONE/API)의
> 스택 표기를 Spring Boot로 정정.
> 2차(이번 판) — PRD를 기준으로 문서 5종 상호 대조. 발견된 불일치 수정, 남은 것은 §5에 결정 필요 항목으로 정리.
> 이 문서를 팀 공유용으로 재구성.

## 한눈에 보기 (전체 대상)

- **Vium**(컨디션 체크인·공백 처리)에서 **Ensom**(AI 웰니스 일정관리 비서)으로 제품이 바뀐다. 앱 이름·핵심 기능이 다르다.
- **개발 스택은 그대로다.** Flutter + Java 21/Spring Boot + PostgreSQL. FE/BE 모두 새로 배우거나 갈아엎을 도구는 없다.
- 문서 기준은 **PRD가 최상위**, 그 아래로 TRD(기술 요구사항) → ERD(DB 스키마) → API 명세 → MILESTONE(작업 분해) 순으로 참조한다. 다섯 문서끼리 어긋나는 부분은 이번에 찾아서 고쳤다.
- 역할별로 볼 것:
  - **FE** — `API.md`가 새 엔드포인트·요청/응답 필드의 기준. 아래 API 표기 정정(§3) 참고.
  - **AI/데이터** — `TRD.md` §5~§9(계획 엔진·웰니스 엔진·오케스트레이터)가 담당 범위. 언어는 BE와 무관하게 독립.
  - **기획/디자인** — `PRD.md`가 기준 문서. 이번 대조에서 PRD와 다른 부분은 전부 PRD 쪽으로 맞췄다.
  - **BE** — 아래 전체, 특히 §2(패키지 판정)·§4(마이그레이션)·§5(결정 필요 항목).

## 왜 재설계가 필요한가

Vium과 Ensom은 스택은 같지만 제품이 다르다 — 컨디션 체크인·공백 처리 중심에서 준비 계획 엔진·웰니스 점수·알림
오케스트레이션 중심으로 바뀌었다. 기존 백엔드 패키지 절반가량은 새 설계에 대응 개념이 없어 폐기 대상이고,
나머지는 단순 재사용이 아니라 "재설계"다(필드가 갈라지거나 늘어남).

## 1. 그대로 둔다 (도구·인프라)

| 대상 | 확인 |
|---|---|
| `build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`, `gradle/` | Java 21 + Spring Boot 4.1.0, TRD §3과 일치 |
| `docker-compose.yml` | PostgreSQL 16, TRD §3과 일치 |
| `.gitattributes` | gradlew 개행 규칙, 도구 체인 유지되므로 필요 |
| `.github/` (PR·이슈 템플릿) | 스택 무관 |
| `.gitignore` | Java/Gradle 항목 유지 |

## 2. 패키지별 재설계 판정 (`src/main/java/com/hq/backend/`)

| 패키지 | 새 ERD/API 대응 | 판정 |
|---|---|---|
| `common/auth`, `common/exception` | 인증 주입·오류 포맷은 스택이 같으므로 그대로 유효 | **유지** |
| `idempotency` | TR-03이 `Idempotency-Key` + `clientEventId` 이중 방어를 그대로 요구 | **유지** — 새 액션 엔드포인트(`/plans/{id}/actions`)에 재사용 |
| `auth` | `POST /auth/login` 소셜 전용(AUTH-01). 이메일·비밀번호 경로(`/auth/email/*`) 폐기, `provider`/`providerUid`가 `USER_IDENTITY`로 분리 | **재설계** |
| `user` | `USERS`(계정 상태·탈퇴) + `USER_IDENTITY`(공급자별 식별자)로 분리. `passwordHash` 컬럼 소멸 | **재설계** |
| `consent` | `USER_CONSENT` 그대로 대응 | **재설계(경미)** — 약관 종류만 갱신 |
| `place` | `USER_PLACE` 대응. 단 `PlaceVisit`(방문 기록)은 새 ERD에 없음 | **재설계** — 방문 기록 기능은 폐기 |
| `calendar` | `CALENDAR_CONNECTION` + `CALENDAR_SOURCE`로 분리. `GET /calendar/density`는 새 API 명세에 없음 | **재설계** — density 기능 폐기 |
| `event` | `EVENT` 대응하나 `title` 컬럼 삭제 확정, `location_state`·`event_kind` 신설(ERD §2) | **재설계** |
| `checkin` (컨디션 추론) | 새 문서에 대응 개념 없음 | **폐기** |
| `gapcheck` (공백 처리) | 대응 개념 없음 | **폐기** |
| `state` (일일 상태) | `DAILY_WELLNESS_SUMMARY`가 있으나 DWL 계산 정의가 완전히 다름 — 사실상 새로 만드는 것과 같음 | **폐기** |
| `routine` | `USER_PREP_RULE`/`/prep-items`로 재편, CRUD 형태만 유사 | **폐기(개념만 흡수)** |
| `adjustment` | `USER_PREP_ESTIMATE` 원인 분리 EMA 보정(TRD §6)이 대체 | **폐기** |

폐기 대상 5개 패키지(`checkin`, `gapcheck`, `state`, `routine`, `adjustment`)는 최근까지 실제로 기능이 완성되어
`dev`에 머지된 코드다(`e5263ff` 등). **삭제는 되돌리기 어려운 작업이라 실행 전에 반드시 확인받는다.**

## 3. 이번에 고친 문서 불일치 (PRD 기준 대조 결과)

- TRD/API.md/MILESTONE.md 곳곳의 스택 표기가 "Python/FastAPI"로 남아 있던 것을 Spring Boot로 정정 (표·다이어그램·의사코드 전부, 의사코드는 Java `record`/`interface`로 변환).
- TRD §12.2 예시 JSON이 snake_case로 남아 있던 것을 API.md의 camelCase 기준에 맞춤.
- **준비 항목(맞춤 준비물) 스키마** — TRD가 자체 정의한 `user_prep_item`(`kind` enum에 `sensitive`/`fromChip` 평면 구조)이 ERD v3의 `USER_PREP_RULE`(구분×동작 2축 구조)과 어긋나 있었다. PRD §11.3이 ERD v3와 같은 2축 분류("챙기기/사용·섭취하기/구매하기/시간이 필요한 루틴" × "반복 준비물/개인 기호 품목/시간 소요 루틴/민감 항목")를 명시하고 있어 **ERD v3 쪽으로 통일**했다. TRD `4.3` DDL, API.md `/prep-items` 요청 필드를 모두 `ruleName`/`ruleCategory`/`actionType`/`ruleTiming`/`defaultMinutes` 기준으로 바꿨다.
  - 이 과정에서 옛 구조의 `fromChip`(추천 칩 선택 여부) 필드를 스키마에서 뺐었는데, PRD §1.1 "위험 12"(칩은 민감·규제 품목을 추천하지 않는다)가 이 구분을 실제로 요구한다는 게 확인돼 **요청 검증용 필드로는 복원**했다. 다만 DB 컬럼으로 영구 저장할지는 미정 — TRD §20에 `D11`로 등록해 뒀다(§6 참고).

## 4. ERD v3 전체 엔티티 ↔ API 명세 전수 대조 결과

ERD.md의 엔티티 28개(신규 6개 포함) 전체와 API.md 나머지 엔드포인트(§4~§19)를 하나씩 맞춰봤다. 문서별로 필요한 조치는 아래.

### API.md — 바로 고침 (같은 개념의 옛 표기가 새 문서 안에 섞여 있던 것)

TRD.md `§4.2` "엔티티 관계" 다이어그램(PDF 추출 아티팩트로 판단해 손대지 않기로 한 그 그림)이 `user_prep_item`/`plan_checklist_item`/`plan_action_log`/`daily_summary`/`prep_time_model`/`user_interest` 같은 ERD v2 이전 표기를 쓰고 있는데, API.md `§16 데이터 모델 요약` 표가 이 옛 표기를 그대로 베껴 온 상태였다. API.md 본문(§6, §9, §12~§15)은 이미 ERD v3 표기로 맞춰져 있어 §16만 따로 놀고 있던 것 — 아래처럼 고쳤다.

| 위치 | 옛 표기 | 새 표기 |
|---|---|---|
| §16 PrepItem | `user_prep_item` / kind·extraMin·sensitive·fromChip | `user_prep_rule` / ruleCategory·actionType·defaultMinutes·isSensitive·fromChip(D11 미정) |
| §16 ChecklistItem | `plan_checklist_item`(ERD에 없는 테이블) | `plan_prep_item` + `plan_wellness_action` 병합(단일 테이블 아님) |
| §16 ActionLog | `plan_action_log` | `event_action_log` |
| §16 DailyWellnessSummary | `daily_summary` | `daily_wellness_summary` |
| §16 PrepEstimate | `prep_time_model` / value·sampleN·lastReason(존재하지 않는 필드) | `user_prep_estimate` / estimatedMinutes·sampleCount·confidence·modelVersion |
| §16 Plan | wisScore가 `plan_context`에 있는 것처럼 표기 | WIS는 `plan_wellness_score`가 별도 테이블임을 명시 |
| §9 계획 응답 checklist | 항목 필드명 `kind` | `actionType`(§6 요청 스키마와 통일) |

### ERD.md — 확인·수정이 필요할 수 있음 (스키마 소유자 결정, 여기서 고치지 않음)

- **`NOTIFICATION.dedup_key` 컬럼이 없다.** API.md §11·§12.3은 `dedup_key = sha1(event_id:slot:revision_no)`에 DB UNIQUE를 걸어 중복 발송을 막는다고 명시하는데, ERD v3 `NOTIFICATION` 엔티티(ERD.md:273-283)에는 이 컬럼이 없다. TRD §4.2의 옛 다이어그램에는 notification 옆에 "dedup_key UNIQUE"가 있었던 걸로 보아 v3에서 빠진 것으로 보인다 — 신설 필요 여부 확인.
- **API 표면이 없는 엔티티 5개** — ERD엔 있는데 API.md 어디에도 대응 엔드포인트가 없다. 각각 다른 엔드포인트 응답에 묻어가는 설계인지, 별도 엔드포인트가 빠진 것인지 확인 필요:
  - `PUSH_DEVICE` — FCM/APNs 토큰을 등록하는 엔드포인트가 없다. 로그인 응답이나 `/me/bootstrap`에 묻어가는지, `POST /push-devices` 같은 별도 경로가 필요한지 미정.
  - `USER_CONSENT` — 약관 동의를 기록하는 엔드포인트가 없다. `idempotency_key` 컬럼이 있는 걸 보면 원래 전용 쓰기 엔드포인트(`POST /consent` 류)가 있었을 가능성.
  - `CALENDAR_SOURCE` — 연결(`CALENDAR_CONNECTION`) 안의 개별 캘린더(구글 캘린더 여러 개 등)를 조회·선택하는 경로가 없다. `/calendar/connections` 응답에 중첩되는지 확인 필요.
  - `EVENT_FEEDBACK` — "너무 일찍/촉박했나요" 같은 사후 평가를 사용자가 입력하는 엔드포인트가 없다. TRD 옛 본문(§6 근처, "feedback '너무 일찍 안내' → notif_lead 축소")은 이 입력이 존재한다는 전제로 쓰여 있다.
  - `EVENT_DELAY_REASON` — 지연 사유(사용자 입력 또는 추론)를 기록하는 경로가 없다. `POST /plans/{id}/actions`에 통합되는지 확인 필요.

### MILESTONE.md — 바로 고침

같은 ERD v2 이전 표기·계산 오류가 MILESTONE.md에도 남아 있었다.

- M0 BE 산출물 "ERD 16+신규 5테이블" — ERD v3는 실제로 전체 28테이블(신규 6개)이다. 옛 표기라 숫자 자체가 틀렸다 → "ERD v3 전체 28테이블(신규 6개 포함)"으로 수정.
- FE M1 산출물 "맞춤 준비 항목(user_prep_item) 등록 UI" — `user_prep_rule`로 수정.
- §3.3 미결 의존 표에 D11(`fromChip` 저장 여부)·`dedup_key` 컬럼·PUSH_DEVICE 등 5개 엔티티 공백을 새 행으로 추가했다. 특히 `dedup_key`는 M2("아웃박스 패턴, dedup_key") 착수 전, `USER_CONSENT`/`PUSH_DEVICE`는 M0가 이미 산출물로 못박은 기능이라 M0 착수 전 확정이 필요하다고 표시해 뒀다.

MILESTONE.md §2의 파트 구성(QA가 실제 팀에 없음)은 이번에도 고치지 않았다 — 누가 그 작업을 맡을지는 문서 대조로 풀 수 있는 문제가 아니라 팀이 정할 일이라 §6-3에 그대로 남겨둔다.

## 5. 손대지 않는다 — Flyway 마이그레이션 (`V1~V4`)

CLAUDE.md 규칙상 마이그레이션 파일(`V*__*.sql`)은 소유자가 백엔드B이므로 **여기서 생성·수정하지 않는다.**
다만 위 §2·§3 판정이 스키마에 요구하는 변경 사항은 기록해 둔다 — 백엔드B가 새 마이그레이션을 만들 때 참고.

- ERD v3 신규 6엔티티(`USER_IDENTITY`, `USER_WELLNESS_PREF`, `PLAN_WELLNESS_SCORE`, `PLAN_WELLNESS_ACTION`,
  `WELLNESS_EVENT_SCHEDULE`, `DAILY_WELLNESS_SUMMARY`) 신설 필요 (ERD §2)
- `USERS.provider`/`provider_uid` → `USER_IDENTITY`로 이관 (ERD §2 제거·축소)
- `EVENT.title` 컬럼 삭제, `location_state`/`event_kind` 신설
- `user_prep_rule`(구 `user_prep_item`) — ERD v3 `USER_PREP_RULE` 그대로. `fromChip` 저장 여부는 D11 결정 대기
- `notification.dedup_key` — §4에서 발견. 컬럼 신설 필요 여부 확인 필요
- `checkin`/`gap_checks`/`daily_states`/`routine*`/`adjustments` 테이블 — §2에서 폐기 판정된 패키지에 대응.
  존치 여부는 백엔드B와 합의 필요 (드롭할지, 당장은 남겨두고 코드만 뗄지)
- TR-06: `engine_config` 테이블을 **M0 스키마 설계 시점에 포함**해야 함. 빠뜨리면 M5에서 코드 배포 없이 파라미터
  확정이 불가능해짐

## 6. 팀에 결정을 요청하는 항목

1. **폐기 대상 5개 패키지를 실제로 삭제해도 되는가.** 최근 완성된 기능이라 확인 없이 지우지 않는다. (BE)
2. **Flyway 마이그레이션 처리 방향** — 옛 도메인 테이블(`checkin`, `gap_checks`, `daily_states`, `routine*`,
   `adjustments`)을 드롭할지, 당장은 스키마에 남겨두고 애플리케이션 코드만 뗄지. (BE, 스키마 소유자 결정)
3. **MILESTONE §2의 파트 구성이 실제 팀 구성과 다르다.** 문서는 "FE / BE / AI·데이터 / QA" 4파트인데, 실제 팀은
   BE 2 · AI 1 · FE 1 · 기획및디자인 2로 QA가 별도로 없다. §17(테스트 전략)·§18(마일스톤)의 "QA" 담당 항목을 누가
   맡을지 정해야 마일스톤 표가 실행 가능해진다. (전체)
4. **TRD D11 — 준비 항목 `fromChip`(추천 칩 선택 여부)을 DB에 영구 저장해야 하는가.** PRD §1.1은 칩 추천 시점의
   민감 품목 배제만 요구하고, 저장된 이력을 다시 쓰는 곳은 아직 문서에 없다. 저장 안 하고 요청 검증에서만
   쓰면 ERD v3를 그대로 둘 수 있다. (BE·기획)
5. **`plan_revision` 스케줄러 컬럼** — TRD가 정의한 `next_eval_at`/`input_hash`/`superseded_by`/`state`/`wis_score`/`wis_ver`가
   ERD v3의 `PLAN_REVISION`(`plan_status`/`calc_version`만 있고 WIS는 별도 `PLAN_WELLNESS_SCORE` 테이블)에 없다.
   PRD는 재계산 로직만 기능적으로 서술하고 스키마 detail은 정하지 않아, PRD 기준으로도 해소가 안 된다.
   재계산 스케줄러 설계를 다시 볼 사람(BE·AI)이 필요. (BE·AI)
6. **`notification.dedup_key` 컬럼 신설 여부** — §4에서 발견. API 두 곳(§11, §12.3)이 이 컬럼의 UNIQUE 제약에
   기대 중복 발송을 막는데 ERD v3엔 없다. (BE)
7. **PUSH_DEVICE·USER_CONSENT·CALENDAR_SOURCE·EVENT_FEEDBACK·EVENT_DELAY_REASON의 API 설계 확인** — §4에서
   발견한 5개 엔티티가 API.md에 대응 엔드포인트가 없다. 다른 엔드포인트에 포함되는 설계인지, 빠진 것인지 확인
   필요 — 특히 `PUSH_DEVICE`(FCM 토큰 등록)와 `USER_CONSENT`(약관 동의 기록)는 M0 로그인 플로우에 필수라 우선순위가
   높다. (BE·기획)

## 7. 갱신이 필요한 기존 규칙 (CLAUDE.md)

- **D-005**(컨디션 추론에서 외부 LLM 호출 금지) — 대상 기능(`checkin`)이 폐기되면 규칙이 가리키는 대상이 사라진다.
  다만 TRD TR-09("웰니스 카피는 승인 템플릿만, 생성 모델 금지")가 같은 취지를 요구하므로 **금지 대상만 웰니스 카피로
  옮겨 존치해야 한다.** 이 문서는 판정만 남기고 CLAUDE.md 자체는 팀 확인 후 수정한다.

## 8. 다음 단계 (M0 · MILESTONE §2.1, BE)

ERD v3 전체 스키마 · 소셜 로그인 + JWT · 약관 동의 저장 · 캘린더 읽기 1종 · Provider 인터페이스 스텁 3종(지도/날씨/캘린더) ·
CI 파이프라인. 완료 판정은 "로그인 → 일정 유입 → 스텁 경로로 계획 생성이 end-to-end로 동작".

## 되돌리는 방법

이 브랜치는 `dev`에서 갈라져 나왔고 `dev`·`origin/dev`는 그대로다.
전환을 취소하려면 브랜치를 버리면 된다: `git checkout dev && git branch -D setting/ensom-reset`
