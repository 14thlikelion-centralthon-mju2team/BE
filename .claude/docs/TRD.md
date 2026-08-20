# Ensom TRD
## TECHNICAL REQUIREMENTS DOCUMENT

v2.0
AI 웰니스 일정관리 비서 · 기술 요구사항 정의서

늦지 않게, 서두르지 않게.
PRD v0.4.3과 최종 ERD를 구현 가능한 시스템 명세로 변환한다.

문서 버전      작성일           상위 문서                                  확정 스택
v2.0       2026-08-16    PRD v0.4.3 · 팀 TRD 초안(8.15) · 최종 ERD   Flutter · Spring Boot · PostgreSQL

대상                      단계
FE · BE · AI/데이터 · QA   MVP / 클로즈드 베타

# 목차
## 1. 문서 개요와 개정 배경                                         12 API 명세
## 2. 시스템 아키텍처                                             13 트랜잭션 · 동시성 · 복구
## 3. 기술 스택 (확정)                                           14 프라이버시 엔지니어링
## 4. 데이터 설계 (ER → 관계 스키마)                                 15 성능 예산과 SLO
## 5. Plan Engine — 준비 계획 엔진                               16 관측성과 지표 수집
## 6. 개인화 모델 (원인 분리 보정)                                    17 테스트 전략
## 7. 웰니스 엔진 (WIS · RLS · DWL)                             18 마일스톤과 개발 순서
## 8. 알림 오케스트레이션                                           19 기술 위험과 대응
## 9. 지오펜싱 기반 출발·도착 확인                                     20 미결 기술 결정
## 10. 인증과 회원 수명주기                                          A 파라미터 레지스트리
## 11. 외부 연동 계층                                             B 요구사항 추적 매트릭스

이 문서를 읽는 법
역할               먼저 읽을 절
백엔드              §4 데이터 설계 → §5 Plan Engine → §7 웰니스 엔진 → §8 알림 → §13 트랜잭션 → §12 API
프론트엔드            §2 아키텍처 → §10 인증 → §9 지오펜싱 → §12 API → §15 성능 예산
AI · 데이터         §6 개인화 → §7 웰니스 엔진 → §4.5 행동 이벤트 → §16 관측성 → §17.2 골든 테스트
QA               §17 테스트 → §15 SLO → §19 위험 → 부록 B 추적 매트릭스
기획               §1 개정 배경 → §7 웰니스 엔진 → §18 마일스톤 → §20 미결
PRD 절은 PRD §14.3 , 기능 요구사항은 WELL-02 형식으로 인용한다. 본 문서가 신설한 기술 요구사항은 TR-xx ID를 가지며 부록 B 말미에 모아 둔다.
데이터 설계는 팀 합의 흐름인 Silberschatz Database System Concepts의 ER 설계 → 관계 스키마 → 애플리케이션 → 트랜잭션/복구 순서를 따르고, 각
장의 적용 위치를 §4.1에 대응표로 명시한다.

## 1. 문서 개요와 개정 배경
PRD v0.4.3은 제품의 무게중심을 옮겼다. 센서 기반 보행자 경험이 빠지고, 그 자리에 환경 기반 웰니스 엔진과 회원 계정이
들어왔다. TRD v1.0의 상당 부분이 무효가 되었으므로 본 문서는 부분 수정이 아니라 v2.0 전면 개정이다.
### 1.1. 개정 요약 (TRD v1.0 → v2.0)
구분      내용                                                    근거
제거      센서 서브시스템 전체(헤딩 융합·방향 부채꼴·나침반 보정), 보행                  PRD §17.2 · §19.2 — MVP 제외 확정
속도 추정, 관련 파라미터 15종과 실측 하네스
신설      웰니스 엔진(WIS·RLS·DWL 점수, 행동 매핑, 이벤트 스케줄러)               PRD §14 · §16.4~16.5 · WELL-01~06
— §7
신설      인증·회원 수명주기(소셜 로그인, 세션, 로그아웃/탈퇴, 계정                    PRD §11 · AUTH-01~04
귀속) — §10
신설      맞춤 준비 항목(user_prep_item)과 일정별 체크리스트 — §4.4,           PRD §11.3 · ONB-01 · SET-02 · PLAN-05
§5.4
확정      기술 스택 Flutter + Spring Boot + PostgreSQL (BE 기존 구조    팀 결정
재사용 확정)
확정      하단 탭 바 채택, 지도는 기본 기능만 — v1.0의 미결 D 항목 다수              PRD §21.5 · §18
해소
흡수      팀 TRD 초안의 ERD 명명(event, plan_revision, plan_context   팀 TRD 초안 (8.15)
…), API 경로 구조, 트랜잭션/복구 章 구성
유지      Plan Engine 순수 함수 + 트레이스, 원인 분리 보정, 알림 예산             PRD §12 · §16 계속 유효
상태 기계, 지오펜싱, 오프라인 멱등성, 시간 규약

### 1.2. 목적
PRD v0.4.3의 기능 요구사항 41건을 컴포넌트·스키마·API 계약으로 분해한다.
PRD가 수식으로 제시한 웰니스 점수(WIS·RLS·DWL)를 입력 정규화, 데이터 부재 처리, 버전 관리까지 포함한 실행 명세로
확정한다.
회원 전용 전환에 따른 인증·세션·삭제·복구 흐름을 정의한다.
외부 의존(지도·환경 데이터·캘린더·푸시·소셜 로그인)의 실패를 설계 시점에 흡수한다.
### 1.3. 범위
구분 포함                                              제외
이번 PRD §25.1 P0 전체 — 회원 인증 · 캘린더 · 계획 엔진 · 기본 경로 · 웹 클라이언트 · 관리자 콘솔(최소 조회만) · 다국어
릴리스 3종 시간 알림 + 웰니스 이벤트 알림 · WIS 계산 · 맞춤 준비 항목 ·
지오펜싱 출발·도착 확인 · 데이터 삭제
구조만 일정 유형별 모델(MODEL-02) · 주간 리포트(REPORT-03) ·      웨어러블 · 학교/기업 일정 연동
선반영 웰니스 빈도 자동 개인화 — 데이터 수집과 스키마는 P0에서 켠다
비목표 비회원 모드 · 지도 환경 레이어 · 센서 헤딩·보행 속도·실내 측위 · 턴바이턴 음성 · 자체 지도 데이터 · 친구 위치 공유 · 건강/피부 진단
(PRD §25.4)

### 1.4. 용어
용어                     정의
일정 (event)             사용자의 약속. 내부 생성·외부 캘린더 동기화·지도 검색 저장의 세 경로로 생긴다.
계획 (plan_revision)     하나의 일정에 대한 준비 시작·출발·도착 시각의 묶음. 불변이며 리비전을 쌓는다. 재계산은 새 리비전을 만든다.
계산 근거 (plan_context)   계획과 웰니스 제안을 만든 입력·가중치·버전의 구조화 스냅샷. PLAN-03과 PRD §20.4의 구현체.
맞춤 준비 항목               사용자가 등록한 반복 준비물·기호 품목·시간 루틴. 챙기기/사용·섭취/구매/시간 루틴의 4종 (PRD §11.3).
WIS / RLS / DWL        일정 웰니스 행동 우선순위 / 촉박함 부담 / 일일 웰니스 부담 점수. 전부 내부 우선순위 값이며 건강 점수가 아니다 (PRD
§14).
활성 창                   준비 시작 30분 전 ~ 도착 확정. 지오펜스 등록과 고빈도 재평가가 허용되는 유일한 구간.
실질 변화                  재계산 결과가 사용자에게 알릴 만큼 달라진 상태. §8.3에서 정량 정의.

## 2. 시스템 아키텍처
구조를 결정하는 문장은 둘이다. 계획과 웰니스 판단의 진실은 서버에 있다 — 앱이 꺼져 있어도 재계산과 푸시가 일어나야
한다. 위치 원시 데이터는 기기를 떠나지 않는다 — 서버는 지오펜스 판정 결과만 받는다. v1.0의 센서 계층이 빠지면서
클라이언트는 한층 얇아졌다.
### 2.1. 전체 구성
CLIENT · Flutter                             BACKEND · Spring Boot                                               EXTERNAL

UI ·   하단 탭 4화면                              API Gateway (Spring Boot) —  인증 · 검증 · 멱등성                          지도·경로 API 미확정 D1
홈 / 지도 / 캘린더 / 설정 · Riverpod                 사용자별 데이터 접근 강제 (PRD §23.4)                                          기본 경로·대체 경로만 사용
계획을 계산하지 않는다 (TR-01)                                                                                             ODsay / 카카오 / TMAP 후보

Plan Engine §5                     Wellness Engine §7
인증 세션 · 소셜 로그인 SDK                           순수 함수 · 트레이스                                계산
WIS·RLS·DWL                      기상청 단기예보
토큰 Secure Storage · 로그아웃 시 소거                체크리스트 합성                           행동 매핑 템플릿 카피
·                         자외선·기온·강수 · 격자(nx,ny)

지오펜스 매니저 §9                          판정만     Orchestrator §8 — 30초 틱 · SKIP LOCKED                               에어코리아
활성 일정만 · 최대 2개 리전                            시간 알림 3슬롯 + 웰니스 이벤트 1슬롯 · 실질 변화 판정                                  미세먼지 · 측정소 매핑
OS 이벤트 → 판정 결과만 업로드                          웰니스 이벤트 게이트: 동의 × 노출 지속 × 주기 도달 (TR-11)

로컬 스토어 (Drift/SQLite)                        Personalization §6                 Calendar Sync Worker
Google / Apple Calendar
읽기 전용 · 토큰 암호화 보관
오프라인 큐 · 계획 캐시                               원인 분리 EMA 보정                       증분 동기화 · 5~15분
알림 액션은 오프라인에서도 기록                            준비/버퍼/알림 시점                        ext_uid 중복 방지
소셜 로그인 IdP 미확정 D2
푸시 수신 · 알림 액션                                Provider Layer §11 — Route / Weather / Air / Calendar / OAuth
Google · Apple · Kakao   후보
완료/미루기/오늘은 그만 원탭                             정규화 · 캐시 · 쿼터 · 폴백. 교체 가능 경계
잠금화면 민감 항목 일반화 (TR-10)

로컬 알림 폴백 TR-07                               PostgreSQL 16                      Push Dispatcher
준비·출발 2건 사전 예약                                      신규 5 테이블
ERD 16 +                           FCM · dedup_key
스케줄 큐 겸용 §13                       collapse_key치환

관측: Sentry + events 테이블(§16) + 감사 로그                                FCM / APNs
전송 보장 없음 → 로컬 폴백

그림 2.1 — 시스템 구성. 청록 테두리가 제품 고유 로직이다. v1.0의 센서 서브시스템이 빠지고 Wellness Engine이 서버 측에 들어왔다.

### 2.2. 컴포넌트 책임 경계
컴포넌트                               책임                                                                    책임이 아닌 것
Plan Engine                        스냅샷 → 3개 시각 + 체크리스트 + 트레이스                                            DB·네트워크·현재 시각 — 전부 주입받는다
Wellness Engine                    환경 스냅샷 + 야외 노출 추정 → WIS·행동 최대 3개, 일일                                  건강 판단. 우선순위만 계산한다 (PRD §14.1)
DWL 요약
Orchestrator                       재평가 시점 결정, 실질 변화 판정, 시간·웰니스 알림 예산                                     계산 자체. 두 엔진을 호출할 뿐이다
집행
Personalization                    행동 이벤트 → 준비 시간·교통 버퍼·알림 시점을 원인별로                                      계획 생성. 값만 제공한다
갱신
Calendar Sync Worker               외부 일정 증분 동기화, 변경·삭제 반영                                                분류·계획. 저장까지만
Provider Layer                     외부 API 호출·정규화·캐시·쿼터·폴백                                                제품 의미 해석
앱 UI                               서버 계획의 표현, 행동 기록, 체크리스트 조작                                            권위 있는 시각 계산. 카운트다운만 로컬

TR-01 · 계획과 웰니스 판단의 권위는 서버가 단독으로 가진다
앱이 종료된 상태에서도 교통 변화로 출발 시각을 앞당기고, 야외 노출 지속 조건을 평가해 재도포 알림을 보내야 한다(PRD
§16.5~16.6). 클라이언트는 서버가 확정한 시각·행동까지 남은 시간을 표시할 뿐이다. 오프라인이면 마지막 계획과 조회 시각을
함께 보여준다(PRD §23.2).

### 2.3. 배포 토폴로지
단일 VM (2 vCPU / 4GB) · Docker Compose
├─ api         Spring Boot (내장 Tomcat)
├─ scheduler   동일 이미지 · 단일 프로세스 — 틱 루프 + 캘린더 동기화 + 일일 요약 배치
├─ db          PostgreSQL 16 (일 1회 덤프 → 오브젝트 스토리지)
└─ proxy       Caddy (TLS 자동)

베타 50~100명(PRD §26.5) 기준. 확장 신호 — 틱 1회 15초 초과 또는 활성 일정 5,000건 초과 — 전까지 브로커·큐 미도입. 배치 4종(캘린더 동기화, 계획
재평가, 알림 발송, 일일 요약)은 전부 스케줄러 프로세스 안의 주기 작업으로 구현한다. 팀 초안 §10의 배치 목록과 1:1 대응한다.

## 3. 기술 스택 (확정)
Flutter + Spring Boot + PostgreSQL은 팀 확정 사항이다
v1.0에서 제안이던 3대 축이 확정되었다. 아래 표의 나머지 항목은 확정 스택 위의 세부 선택이며, 도메인 설계(§4~§8)는 이
선택이 바뀌어도 유효하다.

영역             선택                         근거
앱              Flutter 3.x 확정             4화면 + 하단 탭 바(PRD §21.5). 센서 네이티브 채널이 사라져 플랫폼 코드는 지오펜스·푸시
·Secure Storage만 남는다
앱 상태·로컬        Riverpod + Drift(SQLite)   오프라인 큐와 계획 캐시가 1급 시민. 알림 액션은 망 없이 기록된다
백엔드            Java 21 + Spring Boot 확정   BE 기존 구조 재사용(팀 결정) · AI/데이터는 별도 파트로 언어 독립. 계획·웰니스
엔진은 순수 함수로 두고 JUnit 골든 테스트
DB             PostgreSQL 16 확정           관계 무결성·트랜잭션·유니크/체크 제약(팀 초안 §11.1) + plan_context는 JSONB + 스케줄
큐 겸용(§13)
스케줄러           DB 틱 루프 (SKIP LOCKED)      새 인프라 0개. §2.3 확장 신호 전까지 별도 브로커·큐 미도입
푸시             FCM 단일                     iOS도 FCM 경유. dedup·collapse 전략 §8.4
인증             소셜 로그인(IdP 미확정 D2) →       비회원 모드 없음(AUTH-01). 이메일·비밀번호 미지원 — PRD §11.2가 소셜 로그인만 정의
자체 JWT
지도 SDK·경로      미확정 D1                     Provider 인터페이스로 격리(§11.1). 기본 경로만 쓰므로 요구 표면이 v1.0보다 작다

### 3.1. 시간 처리 규약
TR-02 · 시간대는 이 제품에서 가장 흔한 버그 원인이다
저장은 전부 timestamptz (UTC). 경계에서만 지역 시간으로 변환한다.
일정은 IANA 시간대 문자열( Asia/Seoul )을 함께 저장한다. 외부 캘린더는 부동 시각·타 지역 시간대를 보낼 수 있다.
종일 일정은 계획 대상에서 제외하고 사용자가 시각을 넣을 때만 편입한다.
행동 이벤트는 device_ts 와 서버 수신 시각을 모두 저장하고, 차이 ±120초 초과 시 clock_skew 플래그 → 학습 제외.
엔진은 now 를 주입받는다. 계산 내부에서 datetime.now() 호출 금지 — 테스트에서 시간을 고정하기 위함이다.

## 4. 데이터 설계 (ER → 관계 스키마)
팀 초안의 흐름을 그대로 따른다: ER 설계 → 관계 스키마 → 애플리케이션 → 트랜잭션/복구. 테이블 명명은 최종 ERD를
기준으로 하고, PRD v0.4.3이 신설한 개념(맞춤 준비 항목, 웰니스 행동)만 새 테이블로 추가한다. 설계를 관통하는 비대칭
하나 — 계획은 예측이라 리비전을 쌓고, 행동 로그는 사실이라 절대 수정하지 않는다.
### 4.1. 교재 장(章) ↔ 본 문서 대응
Database System Concepts     본 문서의 구현 위치
Ch.6 ER 모델 — 키·참여 제약·약한 엔티티·다치
§4.2 ERD. 다치 속성(주요 장소, 관심 항목, 준비 항목)은 전부 별도 엔티티. plan_context 는
속성 분리                         plan_revision 에 종속된 약한 엔티티

Ch.7 관계형 설계 — 함수 종속·정규화·갱신 이상§4.3 스키마. 계획/경로/알림/로그 분리. 동일 사실 단일 저장 — 선택 경로는
제거                            selected_route_option_id 한 곳

Ch.8 복합 데이터 타입               JSONB는 두 곳만 — plan_context.snapshot (스키마가 진화하는 근거 묶음),
events.payload (지표). 관계로 표현 가능한 것은 JSONB에 넣지 않는다

Ch.9 애플리케이션 개발 — DB를 상태 저장소로 §5~§8. 입력→계산→알림→피드백→재계산의 사이클이 전부 DB 상태 전이로 표현된다
Ch.17~19 트랜잭션·동시성·복구         §13. 트랜잭션 경계 표, SKIP LOCKED, 멱등성 키, 재시도·아웃박스

### 4.2. 엔티티 관계
users
provider · tz · status
deleted_at
plan_revision 불변                  plan_action_log                 events ( 지표)
revision_no · engine_ver          추가 전용 · 수정 금지                   append-only
event                         1:N   prep_start_at · depart_at         type · device_ts·server_ts      JSONB payload
start_at · tz · title               eta_at · state · feasible         source · confidence             날짜 파티션
user_setting                     place_need (user  우선   )            selected_route_option_id          clock_skew · client_event_id    §16
seed_prep_min                    dest · origin_place_id              next_eval_at ◀ 스케줄 큐              revision_at_event
arrival_buffer_min               anchor · source · kind              input_hash · superseded_by
notif_sensitivity                managed · ext_uid/etag              wis_score · wis_ver
lockscreen_private               classify_conf
1:1         notification
plan_context 약한 엔티티
class time|wellness · slot
user_consent · user_permission                                                                         dedup_key UNIQUE · reaction
약관 이력(보존) · 권한 상태                event_classification_review                     스냅샷 기준시각
trace[] · env     ·
질문·응답 · 재학습 신호                      가중치 버전      (PRD §16.9)
push_device (FCM 토큰)
event_feedback
route_option                            원인 선택
outcome ·
user_place                       calendar_connection
환승            촉박 자기평가
home/school/work
provider ·   토큰 암호화                 rank · total/walk_sec·
outdoor_sec (WIS   입력  )
암호화
★신규
lat·lng·radius_m
wellness_action

체크리스트로 투영                           plan_checklist_item         ★신규   action_key · wis_at_emit
완료 미룸 그만
user_interest                                                        원천: prep_item | wellness          result   /     /
웰니스 관심 항목 · 주기                                                       state 완료/미룸/숨김                                               학습
daily_summary         ★신규
date · dwl_level                                                      prep_time_model
user_prep_item           ★신규     template_id · est|obs 구분                                              value·sample_n·last_reason
REPORT-03 / WELL-05
종
kind 4 · extra_min
+ traffic_buffer · notif_lead

sensitive · alias

그림 4.1 — 최종 ERD 기반 + PRD v0.4.3 신규 개념(★). 청록이 이 제품의 심장부다: 계획 리비전, 행동 로그, 준비 항목, 웰니스 행동.

### 4.3. 핵심 스키마
--   맞춤 준비 항목 ── ONB-01 / SET-02 / PLAN-05 ─────────────────────
-- 테이블명·컬럼은 ERD v3 USER_PREP_RULE을 그대로 따른다(PRD §11.3 구분 표: 구분×동작 2축 분류).
-- action_type 4종은 PRD §11.3 "챙기기/사용·섭취하기/구매하기/시간이 필요한 루틴"을 그대로 옮긴 것 — 마지막
-- 값은 timed_routine 이다(구 초안의 prep_kind.routine 은 폐기, ERD v3와 표기 통일).
CREATE TABLE user_prep_rule (
prep_rule_id   uuid PRIMARY KEY,
user_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
rule_name      text NOT NULL,                -- "영양제", "물·텀블러", 직접 입력
rule_category  text NOT NULL,                -- supplement/medication/personal_item/routine/general_item (PRD §11.3)
action_type    text NOT NULL,                -- carry/consume/purchase/timed_routine
rule_timing    text NOT NULL,                -- pre_departure/post_arrival
default_minutes int,                          -- timed_routine만 값 존재. 준비 시간에 합산 (PLAN-05)
apply_event_kind text,
apply_time_band  text,
apply_place_id   uuid,
apply_weather    text,
is_required    boolean NOT NULL DEFAULT false,
is_sensitive   boolean NOT NULL DEFAULT false, -- 복용약·비공개 루틴. 잠금화면엔 고정 일반화 문구로 표시 (TR-10)
is_active      boolean NOT NULL DEFAULT true,
created_at     timestamptz NOT NULL DEFAULT now(),
deleted_at     timestamptz
);
ALTER TABLE user_prep_rule ADD CONSTRAINT ck_prep_minutes CHECK (
  (action_type = 'timed_routine' AND default_minutes IS NOT NULL)
  OR (action_type <> 'timed_routine' AND default_minutes IS NULL)
);

--   계획 리비전 ── 불변. 재계산은 새 행 ──────────────────────────────
CREATE TABLE plan_revision (
id             uuid PRIMARY KEY,
event_id       uuid NOT NULL REFERENCES event(id) ON DELETE CASCADE,
revision_no    int   NOT NULL,
engine_ver     text NOT NULL,                --   상수 변경 시 증가 (TR-06)
prep_start_at timestamptz NOT NULL,
depart_at      timestamptz NOT NULL,
eta_at         timestamptz NOT NULL,
feasible       boolean NOT NULL DEFAULT true,
selected_route_option_id uuid,               -- 단일 저장 (팀 초안 §7.5)
wis_score      smallint,                     -- 0~100. 환경 데이터 없으면 NULL
wis_ver        text,                         -- 가중치 버전 (PRD §16.9)
state          text NOT NULL,                -- §8.1 상태 기계
next_eval_at   timestamptz,                  -- NULL이면 큐에서 빠짐
input_hash     text NOT NULL,
superseded_by uuid REFERENCES plan_revision(id),
UNIQUE (event_id, revision_no)
);
CREATE INDEX plan_due ON plan_revision (next_eval_at)
WHERE next_eval_at IS NOT NULL AND superseded_by IS NULL;

CREATE TABLE plan_context (                       --   약한 엔티티. PLAN-03 / PRD §16.9·§20.4
plan_revision_id uuid PRIMARY KEY REFERENCES plan_revision(id) ON DELETE CASCADE,
trace      jsonb NOT NULL,    -- [{label,minutes,reason,source,adjusted}]
env        jsonb,             -- {uv,pm,temp,rain, base_time, provider, quantized}
weights    jsonb              -- WIS   가중치·임계 스냅샷 — 실험 비교용
);

--   행동 로그 ── 추가 전용. UPDATE/DELETE 금지 ───────────────────────
CREATE TYPE action_type AS ENUM
('prep_started','preparing','departed','arrived','snoozed','skipped',
'plan_edited','checklist_done','wellness_done','wellness_later','wellness_stop');
CREATE TABLE plan_action_log (
id bigserial PRIMARY KEY,
user_id uuid NOT NULL, event_id uuid NOT NULL,

revision_no int, type action_type NOT NULL,
device_ts timestamptz NOT NULL, server_ts timestamptz NOT NULL DEFAULT now(),
source text NOT NULL,             -- manual | notification_action | geofence
confidence real, clock_skew boolean NOT NULL DEFAULT false,
client_event_id uuid NOT NULL,
UNIQUE (user_id, client_event_id)           --   오프라인 재전송 흡수 (TR-03)
);

--   알림 ── 시간·웰니스 공용, 클래스 분리 ────────────────────────────
CREATE TYPE notif_class AS ENUM ('time','wellness');
CREATE TABLE notification (
id uuid PRIMARY KEY,
event_id uuid NOT NULL, revision_no int NOT NULL,
class notif_class NOT NULL,
slot   text NOT NULL,                   여유|B극한|C돌발 · wellness: W
-- time: A
dedup_key text NOT NULL UNIQUE, -- 중복 발송 구조적 차단 (§13)
scheduled_at timestamptz, sent_at timestamptz,
reaction text, reacted_at timestamptz
);

TR-03 · client_event_id 는 선택이 아니라 필수다
알림 액션은 지하철에서 눌리고 나중에 재전송된다. 재전송이 두 번 도착하면 "준비 시작"이 두 번 기록되고 개인화 모델이 잘못된
편차를 학습한다. 클라이언트 생성 UUID에 유니크 제약을 걸어 서버가 조용히 흡수한다. duplicated 는 오류가 아니라 정상
응답이다.

### 4.4. 정규화 결정 노트 (Ch.7)
결정                                  이유
준비 항목 원형과 일정별 체크리스트를                원형 수정이 과거 일정의 기록을 바꾸면 안 된다(갱신 이상). 체크리스트는 계획 생성 시점의 투영
분리                                  사본이며 완료 상태는 사본에만 붙는다
user_prep_item vs
plan_checklist_item

선택 경로는                              동일 사실 단일 저장. 경로 후보(route_option)와 선택(plan_revision)의 함수 종속을 분리한다
selected_route_option_id한곳
WIS 점수는 plan_revision 에, 근거는        점수는 조회·정렬 대상(관계형), 근거는 진화하는 구조(JSONB). Ch.8의 복합 타입은 이 경계에만 허용
plan_context 에

웰니스 행동 결과를                          REPORT-01(일정 결과)과 REPORT-02(웰니스 행동 결과)는 수집 시점·주체·빈도가 다르다. 합치면
event_feedback 과 분리                 NULL 투성이 테이블이 된다
user_consent 는 삭제 정책에서 제외           감사 추적 목적 보존 우선(팀 초안 §17). 탈퇴 시에도 법정 기간 보존 후 파기

### 4.5. 데이터 등급과 보존
데이터                        등급      저장·암호화                 보존 · 삭제
일정 시각·장소·유형                일반      서버 DB                  탈퇴 시 CASCADE 삭제
일정 참석자·메모                   —      수집 안 함                 허용 목록 파싱 — 컬럼 자체가 없다 (PRD §20.2)
주요 장소 좌표                   민감      애플리케이션 레벨 암호화          개별 삭제 가능
맞춤 준비 항목                   민감      암호화 · sensitive        개별 삭제 · 개인화 초기화와 독립 (PRD §20.3)
항목은 잠금화면 일반화
행동 로그                      일반      서버 DB                  13개월 — 주차별 추이 지표에 1년 비교 필요
지오펜스 이벤트                   민감      판정 결과만 — 좌표 궤적 컬럼      90일
없음
환경 스냅샷(양자화)                일반      plan_context           계산 기준값·버전만, 필요 기간만 (PRD §20.2)
웰니스 행동·알림 반응               일반      서버 DB                  13개월 · 웰니스 선호 초기화 시 선호만 삭제
캘린더·IdP 토큰                 민감+     암호화(시크릿 매니저 키)         연결 해제·탈퇴 시 즉시 폐기
동의 이력                      감사      서버 DB                  탈퇴 후에도 법정 기간 보존
삭제 정책 3단(DATA-01/02, PRD §11.5): 로그아웃 = 서버 유지 + 로컬 토큰·민감 캐시·예약 로컬 알림 소거 / 개인화 초기화 = prep_time_model·웰니스 선호
초기화, 행동 로그는 유지 / 탈퇴 = 하드 삭제(동의 이력 제외), 재가입 시 복구 없음.

## 5. Plan Engine — 준비 계획 엔진
사용자가 왜 12시 25분이죠?라고 물었을 때 답하지 못하면 PLAN-03도 신뢰도 없다. 엔진은 시각을 계산하는 함수가 아니라
시각과 근거와 체크리스트를 함께 만드는 순수 함수다. v2.0에서 커진 것은 체크리스트다 — 맞춤 준비 항목과 웰니스 행동이
여기서 합류한다.
### 5.1. 설계 원칙
원칙                      구현
순수 함수                   DB·네트워크·현재 시각을 만지지 않는다. 전부 PlanInput 으로 주입. 같은 입력은 항상 같은 계획.
근거 동시 생성                모든 계산 단계가 TraceItem 을 남긴다. 트레이스는 사후 재구성이 아니라 계산의 부산물.
버전 고정                   상수 하나가 바뀌면 engine_ver 가 올라간다. 과거 계획을 그때의 엔진으로 재현할 수 있다.
계산 중단 없음                입력이 없으면 기본값으로 진행하고 degraded[] 에 기록. 환경 데이터 부재 시 웰니스만 생략, 시간 계획은 정상
(PRD §23.2).

### 5.2. 입출력 계약
record PlanInput(
Instant now,
EventSnapshot event,            // start_at, tz, anchor, dest, kind
GeoPoint origin,
PrepEstimate prepMinutes,       // value, source(seed|model), sample_n, reason
int trafficBuffer,              //   개인화 대상 (§6)
int arrivalBuffer,
RouteOption route,              // nullable. 외부 API 정규화. outdoor_sec 포함
EnvSnapshot env,                // nullable. 양자화된 uv/pm/temp/rain (§7.2)
List<PrepItem> prepItems,       // 활성 맞춤 준비 항목 (apply_rule 통과분)
List<WellnessAction> wellness,  // Wellness Engine이 고른 행동 ≤3 (§7)
EngineConfig config              // 부록 A
) {}

record PlanResult(
Instant prepStartAt,
Instant departAt,
Instant etaAt,
boolean feasible,                // false = 지금 출발해도 늦는다
List<TraceItem> trace,           // 정렬하지 않는다 — 표시는 클라이언트 몫
List<ChecklistItem> checklist,   // 맞춤 항목 + 웰니스 행동 통합 투영
List<String> degraded
) {}

### 5.3. 계산 파이프라인
① 목표 도착       = event.start_at − arrival_buffer
② 권장 출발    목표 도착 − route.total_sec − traffic_buffer
=
③ 준비 시작    권장 출발 − prep_minutes
=
− 상황별 추가 준비 (강수 등, env 기반)
− Σ routine 항목의 extra_min          ← PLAN-05
④ 제약 해결 = depart_not_before 등 시각 제약 적용, 충돌 시 feasible=false
⑤ 체크리스트 = prep_items 투영 + wellness 행동 병합 (§5.4)
anchor = 'depart_at'   인 일정(지도 검색 저장)은 ②를 고정하고 ①을 앞으로 계산한다 (PRD §10.3).

각 단계는 TraceItem을 1개 이상 남긴다. 계산 예시는 PRD §12.4와 동일하며 골든 테스트 01로 고정한다(§17.2).

### 5.4. 체크리스트 합성 규칙 (PLAN-05 · ONB-01)
입력 A — user_prep_item (사용자가 등록한 사실)
kind=routine → 준비 시간에 extra_min 합산 + 체크리스트 표시
kind=carry|consume|purchase → 시간 계산 없이 체크리스트만
apply_rule 불충족 항목은 제외 (MVP: always만, 조건 자동 적용은 P1)

입력 B — wellness 행동 (환경이 만든 제안, §7)
WIS 구간에 따라 0~3개. 항상 근거(reason)를 동반한다

병합 규칙
· 같은 대상 중복 제거: 사용자가 "선크림"을 등록했고 웰니스도 선크림을 제안하면
→ 사용자 항목 1개로 합치고 근거만 웰니스 것을 붙인다 ("자외선 높음 · 야외 45분")
· 표시 상한: 맞춤 항목 3개 + 웰니스 3개, 나머지는 more로 접는다 (PRD §10.2)
· 정렬은 하지 않는다. 항목에 origin(user|wellness)과 tag만 붙인다 — 화면 순서는
PRD §31 미결(웰니스 카드 우선순위)이므로 클라이언트가 결정한다

TR-04 · 엔진은 사용자 등록 사실을 판단하지 않는다
영양제·복용약·기호 품목은 사용자가 등록한 준비 항목으로만 다룬다. 엔진은 섭취 권장·용량·효능·건강 추론을 하지 않으며(PRD
§14.8), 시각 제약("8시 전 출발 불가")이 지각을 유발해도 제약을 깨지 않고 feasible=false 로 사용자에게 돌려준다. 제약
완화는 사람이 결정한다. 코드 리뷰 체크 항목.

### 5.5. 재계산과 멱등성
input_hash = sha256(canonical_json({
event.start_at, origin, dest, anchor,
prep_minutes.value, traffic_buffer, arrival_buffer,
route.id, route.total_sec, route.outdoor_sec,
env.quantized,                      --   원값이 아니라 의사결정 구간 (§7.2)
active_prep_item_ids_and_minutes,
engine_ver, wis_ver
}))

재평가: 해시 동일 → next_eval_at만 갱신, 외부 호출 0회
해시 상이 → 엔진 실행 → 실질 변화 판정(§8.3) → 새 리비전 또는 조용한 갱신

## 6. 개인화 모델 — 원인 분리 보정
PRD §16.2가 v0.4.3에서 명시적으로 요구를 올렸다. 준비 시작 지연, 실제 준비 초과, 출발 지연, 교통 지연을 하나로 합치지
않고 원인별로 각각 준비 시간·알림 시점·교통 버퍼를 조정한다. 잘못된 관측 하나가 계획을 20분 흔들면 사용자는 그 즉시
제품을 믿지 않는다 — 그래서 이 절의 절반은 알고리즘이 아니라 표본 자격과 가드레일이다.
### 6.1. 학습 표본 자격 (MODEL-01)
필터                               조건                                이유
완결성                              prep_started   · departed 모두 존재   둘 중 하나면 D_actual 불성립
시계 정합                            clock_skew = false                기기 시각 오류 배제 (TR-02)
건너뜀 제외                           outcome ≠ skipped                 PRD §16.2
이상치 절단                           0 < D_actual ≤ 240  분             "준비 시작" 누르고 잊은 경우 제거
출처 신뢰                            geofence 유래는 confidence ≥ 0.6     오판 학습 차단 (§9.3)
일정 유효                            사후 삭제·시각 변경 없음                    계획값 자체가 무효

### 6.2. 원인 분리 라우팅
TR-05 · 하나의 관측은 정확히 하나의 손잡이만 조정한다
지각했다는 사실만으로 준비 시간을 늘리면, 교통 때문에 늦은 날에도 준비 시간이 늘어난다. 다음 날 사용자는 이유 없이 20분
일찍 깨워지고, 보정 전체를 꺼 버린다. 편차를 원인별로 라우팅하는 것이 이 모델의 핵심이며 PRD v0.4.3이 명문화한 요구다.

관측 분해 (§4.3의 로그 조인)
Δprep     = t_actual_prep     − t_plan_prep    시작을 미룸
#
Dactual   = t_actual_depart − t_actual_prep    실제 준비 소요
#
Δdepart = t_actual_depart − t_plan_depart    # 출발 지연 (누적 결과)
transit = t_arrive − t_actual_depart vs route.total_sec # 교통 오차

라우팅 — 각 편차는 자기 손잡이에만 간다
Dactual > prep_minutes          개인 준비 시간)
→ P (            EMA 갱신
Δprep 반복 > 0 · snooze ≥ 2 → notif_lead (알림 시점)    준비 알림을 앞당김. P는 불변
transit 오차 반복             → traffic_buffer          경로 신뢰 보정. P는 불변
feedback "너무 일찍 안내"      → notif_lead 축소 · early 신호

P   갱신 (PRD §16.2 공식 유지)
P ← (1−α)·P + α·Dactual                      α = 0.30
outcome ∈ {late, tight}인 회차      α ×1.5   — 실패가 더 강한 신호
outcome = too_early 인 회차         α ×0.7   — 줄이는 방향은 신중히
가드레일: P ∈ [10, seed×2] · 1회 변화 ≤ 15분 (PRD §16.2 상한)
콜드 스타트: 표본 < 3 → 시드 유지. 단 첫 명확한 실패는 1회 보정 (상한 20분)

(도착 여유 > EARLY_MIN)는 "서두르지 않게"의 반대편 실패다(PRD §8.2). 늘리는 신호(×1.5)보다 줄이는 신호(×0.7)를 약하게 두는 비대칭은
too_early

의도된 설계다 — 이른 도착 한 번이 다음 지각을 만들면 안 된다.

### 6.3. 되돌리기와 초기화
POST /plans/{id}/personalization/revert
직전 값 복원 + 해당 표본 excluded_by_user = true (영구 제외)
→ prep_time_model
→같은 보정이 다음 틱에 재발하지 않는다. 되돌림률은 가드레일 지표 (PRD §24.6)
개인화 초기화(DATA-02): prep_time_model·notif_lead·traffic_buffer 초기화.
행동 로그는 유지 — 사용자가 다시 켤 수 있어야 한다.

### 6.4. 일정 유형별 분리 (MODEL-02 · P1)
키 (user_id, event.kind, time_band) · 유효 표본 ≥ 10에서 승격, 미달 시 전역 폴백
classify_conf ≥ 0.7 인 일정만 유형 키에 편입 — 불확실한 분류로 표본을 쪼개지 않는다

## 7. 웰니스 엔진 — WIS · RLS · DWL
PRD §14는 수식까지 제시했다. 남은 것은 구현의 세부다 — 정규화 함수, 데이터가 없을 때의 동작, 야외 노출 시간의 출처,
그리고 점수가 절대 건강 판단으로 새지 않게 막는 경계. 이 절이 v2.0의 새 심장이다.
### 7.1. 점수 정의 (PRD §14.3~14.5)
일정 웰니스 우선순위
WIS = min(100, 100·(0.35·U + 0.25·P + 0.20·T + 0.20·O) · M)                             #
RLS = min(100, 100·(0.45·Dp + 0.35·Dd + 0.20·E))       # 촉박함 부담
DWL = 0.6·(일정별 WIS의 야외시간 가중평균) + 0.4·(일정별 RLS 평균) # 일일 부담

가중치·구간은 초기 휴리스틱 — 전부 원격 설정(TR-06)으로 빼고 wis_ver로 버전을 남긴다.
PRD §31이 베타 A/B로 조정하기로 확정한 값이다.

### 7.2. 입력 정규화 명세
PRD는 "0~1 정규화값"이라고만 적었다. 구현이 필요한 것은 경계값이다. 아래 표가 그 확정 제안이며, 전부 원격 설정이다.
항            원천                                    정규화 (제안 초기값)                                        데이터 부재 시
U            기상청 자외선지수 (출발~도착                      UV 0→0 · 6→0.6 · 8→0.8 · 11+→1.0 선형 구간              U=0, degraded 기록
시간대)
P            에어코리아 PM2.5/PM10 등급                   좋음 0 · 보통 0.25 · 나쁨 0.7 · 매우나쁨 1.0                  P=0, degraded 기록
T            체감온도 + 강수                             쾌적 구간(5~28℃) 0 → 폭염·한파 경계에서 1.0 선형.                 T=0
강수 heavy면 +0.3 후 클램프
O            경로의 도보 구간 합 =                         min(1, 야외 분 / 120) — PRD 명시 상한 120분                 경로 없으면 O=0 → WIS 자체를 생략
route.outdoor_sec
M            user_interest 관심 항목                   기본 1.0 · 관심 항목 관련 시 1.25까지                          1.0

quantize(env) — Plan Engine                 해시와 공유 (§5.5)
rain: none|light(≥30%)|heavy(≥60%)                    ·   uv: low|high(≥6)
pm:  good|bad|very_bad      · temp: cold|mild|hot (+일교차 플래그)
원값이 아니라 이 구간이 바뀔 때만 재계산이 일어난다 — 강수확률 61%→63%는 무변화다.

### 7.3. 파이프라인과 행동 선택
0~39 —   조용히
환경 스냅샷                                                        일정 상세 카드에만
기상청·에어코리아                                                     푸시 없음
기준시각·제공자 기록                     WIS   계산                                                   행동 매핑                       템플릿 카피
(PRD §16.4)
U·P·T·O 정규화 → 가중합             40~69 —   준비 카드                     규칙표
PRD §14.6                   사전 승인 문구만
M 관심 보정                       외출 전 행동 1~2개                 최대 개 중복 제거
## 3. ·                      생성 모델 금지
wis_ver 기록                                                 근거 문자열 생성
야외 노출 추정                                                      체크리스트 병합 §5.4                                            (TR-09)

route.outdoor_sec
=도보      합 상한
leg ·        분
## 120. 70~100 —   이벤트 후보
행동 제안 + 동의 시
일정 중 웰니스 푸시 §7.4

그림 7.1 — 웰니스 파이프라인. 환경 수치는 구간으로 잘리고, 점수는 노출 결정에만 쓰이며, 문장은 템플릿에서만 나온다.

TR-09 · 웰니스 카피에 생성 모델을 사용하지 않는다
PRD §14.8은 진단·치료·복용량·피부 판정·효능 보장을 금지하고, §30은 의료 해석 콘텐츠 검토 통과를 출시 기준으로 걸었다.
자유 생성 LLM은 이 경계를 확률적으로만 지킨다. 사전 승인된 템플릿(PRD 부록 B.4) + 슬롯 치환만 사용하고, 템플릿 외
문자열이 사용자에게 나가는 경로가 없음을 CI 검사로 강제한다(§17.5).

### 7.4. 웰니스 이벤트 스케줄러 (NOTI-04 · WELL-04)
발사 조건 — 전부 AND (PRD §12.7)                                TR-11
① 사용자가 항목과 이벤트 알림을 켬 (user_interest)
② WIS ≥ WELLNESS_EVENT_MIN (70)
③ 야외 노출 지속: 일정이 진행 중 ∧ 경로 야외 구간 잔여 ∨ 야외 일정 유형
④ 사용자 설정 재알림 주기 도달 (사용자가 정한 값 — 앱이 정하지 않는다)
⑤ 같은 일정·같은 행동에 완료/오늘은 그만 없음
취소: 일정 변경 · 도착 확정 · 사용자 완료 입력 · 실내 전환 추정 시 예약 철회
빈도: 일정당 기본 1회 (PRD §13.5) · dedup_key = sha1(event:W:action:revision)
백오프: '오늘은 그만' → 당일 해당 행동 전체 중단
연속 2회 무시 → 해당 항목 빈도 1단계 하향, 설정에 안내 배지 (PRD §14.7)
액션: 완료 / 30분 뒤 / 오늘은 그만 → plan_action_log(wellness_*) + wellness_action

선크림 재도포가 대표 케이스다: 주기는 사용자 설정값이고 서비스는 SPF·피부 타입·제품 성능을 판단하지 않는다(PRD §14.7). 알림 문구에 건강 효과를 확정
표현하지 않는다(§13.5).

### 7.5. 일일 마무리 카드 (WELL-05 · REPORT-03)
생성: 당일 마지막 관리 일정 종료 시 (스케줄러 배치)
입력: 일정 수 · 일정별 WIS·RLS · 야외 이동 합(est|obs 구분) · 극한 알림 발생
DWL → 낮음|보통|높음 수준으로만 변환 — 숫자 노출 여부는 PRD §31 미결(D8)
템플릿 선택 우선순위: 촉박 > 일정 밀도 > 환경 노출 > 안정적 흐름 > 기본
데이터 부족: 관리 일정 0 → 미노출 · 야외 추정 불가 → 수치 없는 문장 (숫자를 지어내지 않는다)
제외: 개인 특이 항목·복용약은 요약 입력에서 원천 배제 (PRD §14.8)

## 8. 알림 오케스트레이션
v0.4.3의 알림은 두 체계다. 시간 알림(여유·극한·돌발)은 일정당 3회, 웰니스 이벤트는 별도 동의 아래 일정당 1회. 두 예산을
한 상태 기계가 집행하되, 서로의 슬롯을 침범하지 않는다.
### 8.1. 계획 상태 기계

ARRIVED
도착 확정 · 학습
지오펜스/원탭
PLANNED                       NOTIFIED    PREPARING          ENROUTE                                    CLOSED
계획 생성됨                       여유 알림 발송     준비 시작 기록          출발 확인 · 웰니스 창                             outcome ·   학습 · 요약 집계
불확실
UNRESOLVED
판정 실패 → 1탭 질문
시간 알림 예산 — 일정당 3회
A 여유 · B 극한 · C 돌발(최신 1건 유지·교체)
상태 입력 시 남은 슬롯 소각 (PRD §13.5)
웰니스 이벤트 — 별도 W 슬롯 · 기본 1회                  SKIPPED           CANCELLED
동의×WIS×주기×노출 지속 (TR-11) · 시간 예산과 독립       이번 일정 제외          일정 삭제·변경

그림 8.1 — 상태 기계. ENROUTE가 웰니스 이벤트의 유일한 발사 창이다. 알림은 상태 전이의 부수 효과이며 독립적으로 발사되지 않는다.

### 8.2. 스케줄링과 적응형 재평가
-- 30    초 틱. Postgres가 지연 큐다 (§13)
SELECT id FROM plan_revision
WHERE next_eval_at <= now() AND superseded_by IS NULL
AND state IN ('PLANNED','NOTIFIED','PREPARING','ENROUTE')
ORDER BY next_eval_at FOR UPDATE SKIP LOCKED LIMIT 200;

구간                                         주기           비고
준비 시작 6시간 전~                               60분          날씨 예보만 유의미. 경로 재조회 없음
6시간 ~ 90분 전                                20분          환경 구간 변화 감시. 경로는 캐시
90분 전 ~ 준비 시작                               5분          교통 반영 시작 — 경로 재조회 구간
준비 시작 ~ 출발                                  3분          가장 민감한 구간
이동 중 (ENROUTE)                              5분          도착 예정 갱신 + 웰니스 이벤트 조건 평가

### 8.3. 실질 변화 판정
Δ = |new.depart_at − cur.depart_at|
Δ < 2    분      리비전조차 만들지 않음  →
## 2. ≤ Δ < 5      분새 리비전 · 홈 갱신 · 푸시 없음 (로그만, PRD §13.3)
→
Δ ≥ 5분        → 새 리비전 + 돌발 슬롯 사용
즉시 알림 예외    → feasible true→false · 경로 수단 변경 · 강수 none→heavy
자동 보정으로 준비 시각이 당겨진 사실 자체는 푸시하지 않는다 — 홈·로그에서만 설명.

### 8.4. 멱등성 · 취소 · 폴백
dedup_key = sha1(event_id : slot : revision_no)   -- notification.dedup_key UNIQUE
collapse_key = event_id:slot                   -- 트레이에 항상 최신 1건만
상태 입력 → cancel_pending_slots + push 회수 + 재계산. 오프라인에서도 로컬 예약 먼저 취소.

TR-07 · 시각이 중요한 알림은 로컬 알림으로 이중화한다
FCM은 전송 시각을 보장하지 않는다. "지금부터 준비하세요"가 12분 늦으면 제품 핵심이 무너진다. 계획 확정 시 클라이언트가
준비 시작·출발 임박 2건을 로컬 알림으로 예약하고, 서버 푸시가 먼저 오면 로컬을 취소한다. 동일 dedup_key를 로컬 식별자로
사용한다. 돌발·웰니스는 예측 불가이므로 서버 푸시 단독.

## 9. 지오펜싱 기반 출발·도착 확인
PRD §12.9·§16.7은 유지됐다. 설계 목표는 정확도가 아니라 최소성이다 — 위험 7(상시 추적 오해)이 현실이 되는 순간 위치
권한 철회가 시작된다.
### 9.1. 등록 예산
TR-08 · 지오펜스는 활성 계획 1건 · 리전 2개로 제한한다
iOS는 앱당 모니터링 리전 20개가 한도이고 초과분은 오류 없이 무시된다. 활성 창(준비 시작 30분 전)에 진입한 계획에만 출발지
EXIT·목적지 ENTER 2개를 등록하고, 이전 계획이 CLOSED 되면 다음 계획으로 넘긴다. 겹치면 시작 시각이 빠른 쪽 우선.

### 9.2. 생명주기와 신뢰도
활성 창 진입 → 출발지 EXIT 리전 (r=130m) + 목적지 ENTER 리전 (r=100~200m, 유형별)
EXIT  → departed 후보 · state ENROUTE · 출발지 리전 해제
ENTER → 체류 90초 검증 → arrived 확정 · 리전 전체 해제
일정 시작 +30분 무신호 → UNRESOLVED → 홈 카드에서 1탭 확인 (푸시 아님)
체류충족 0.2 + 정확도<50m 0.15 + 예상도착 ±20분 0.15
confidence = 0.5 +
경계 진동(60초 내 반복) 0.3
−
≥ 0.6 자동 확정 · 0.4~0.6 조용한 확인 요청 · < 0.4 UNRESOLVED

경계 진동은 억제하지 않고 신뢰도를 깎는다 — 진동 자체가 "판정이 불확실하다"는 정보다. 콜백 안에서 네트워크를 기다리지 않는다: 로컬 기록 →
WorkManager/BGTask로 동기화(TR-02의 clock_skew 검사 적용). PRD §12.10 — confidence ≥ 0.6이면 피드백 UI 자체를 띄우지 않는다. 질문은 데이터가
없을 때의 보완 수단이다.

### 9.3. 권한 부재·실패 시
상황                               동작
위치 권한 거부                         홈 원탭 "출발했어요/도착했어요". 저하 안내를 반복하지 않는다
"사용 중" 권한만                       포그라운드 복귀 시 사후 판정 시도 → 수동 폴백
등록 실패(한도 등)                       geofence_register_failed 지표만. 사용자 비노출

도착 미검출                           UNRESOLVED → 질문 1회, 원인 선택지는 실패 판단 불가 시에만 (PRD §12.10)

## 10. 인증과 회원 수명주기
v0.4.3의 가장 큰 구조 변화다. 비회원 모드가 없다(AUTH-01). 모든 데이터가 계정에 귀속되고, 로그아웃·탈퇴·재가입의 각
단계에서 무엇이 남고 무엇이 사라지는지가 명세의 대상이 된다.
### 10.1. 인증 흐름
소셜 로그인 (IdP 미확정 — Google·Apple·Kakao 후보, D2)
① 클라이언트: IdP SDK → id_token 획득
② POST /auth/login {provider, id_token}
③ 서버: IdP 공개키로 검증 → users upsert (provider, provider_uid UNIQUE)
④ 발급: access JWT 1시간 · refresh 30일 (기기별, push_device와 연결)
⑤ 클라이언트: Secure Storage 보관. 메모리 외 평문 저장 금지
주의 — Apple 로그인: iOS에서 타 소셜 로그인을 제공하면 Apple 로그인 병행이
심사 요건이다. IdP를 하나만 고르더라도 iOS 출시 시 Apple은 사실상 필수.
세션(AUTH-03): access 만료 → refresh 갱신. refresh 실패 시에만 로그인 화면.
로그인 화면으로 보내기 전에 오프라인 여부를 먼저 구분한다 — 지하철에서
토큰 갱신 실패로 로그아웃시키면 안 된다.

### 10.2. 수명주기별 데이터 처리 (AUTH-04 · PRD §11.5)
전이              클라이언트                                       서버
로그아웃            토큰·민감 캐시(준비 항목·장소) 소거 · 예약 로컬 알림 전체 취소      refresh 폐기 · push_device 비활성 · 데이터 유지
· 오프라인 큐는 전송 후 소거
재로그인            bootstrap 재수신 → 계획·설정·준비 항목 복구 (PRD §7.7)   동일 provider_uid → 기존 계정 연결
탈퇴              로컬 전체 소거                                    동기 하드 삭제(CASCADE) — 캘린더 연결·토큰 즉시
폐기, 동의 이력만 법정 보존. 삭제 항목을 완료 화면에
명시
재가입             신규 온보딩                                      이전 개인화 복구 없음 (PRD §11.5 명시)
모든 API는 JWT의 user_id로 행 수준 접근을 강제한다(PRD §23.4). 교차 사용자 접근은 404로 응답해 존재 여부도 노출하지 않는다.

## 11. 외부 연동 계층
지도 제공자는 여전히 미확정이다(PRD §31). 달라진 것은 요구 표면이다 — 기본 경로와 대체 경로만 쓰므로 v1.0보다 계약
조건이 단순해졌고, 대신 환경 데이터가 웰니스 엔진의 1급 입력으로 올라왔다.
### 11.1. 제공자 추상화
interface RouteProvider {
List<RouteOption> search(GeoPoint origin, GeoPoint dest, String anchor, Instant at);
}

record RouteOption(               //   제공자 중립 정규화
String id, String rank,           // rank: fastest | least_walk | least_transfer
int totalSec, int walkSec, int transfers,
int outdoorSec,                   //   도보 leg 합 — WIS의 O 입력 (§7.2)
List<Leg> legs,                   // mode, sec, distance_m
Instant departAt, Instant etaAt,
String provider, String rawRef    //   재조회 키. 원본 응답은 저장하지 않는다 (약관)
) {}

outdoor_sec 이 v2.0에서 이 인터페이스의 가장 중요한 필드다. 보행 속도 보정이 빠지면서 도보 시간 자체는 API 값을 그대로 쓰지만, 야외 노출 추정은
웰니스 엔진 전체의 입력이 된다. 지하 환승 구간을 야외로 계산하면 WIS가 과대평가되므로, 제공자가 leg 유형(지하/지상)을 구분하는지가 D1 선정 기준에
들어간다.

### 11.2. 환경 데이터
기상청 단기예보 좌표→격자(nx,ny) 변환 · 캐시 (nx,ny,base_time) · TTL 초단기 30분/단기 3h
에어코리아     측정소 매핑 캐시 · TTL 1h
공급: 출발~도착 시간대 구간값 → §7.2 양자화 → plan_context.env에 기준시각·제공자 기록
부재: 웰니스 생략 + 시간 계획 정상 (PRD §23.2 · 위험 9). "기준 12:31" 표기.

### 11.3. 캘린더 동기화
Google Calendar 우선 (D2의 IdP와 OAuth 동의 통합). 읽기 전용.
주기 5~15분 폴링 + 수동 /calendar/sync (팀 초안 §10.1). ext_uid·etag 증분 반영.
변경 감지 → 해당 일정 재계산 트리거. 삭제 → 계획 CANCELLED · 예약 알림 회수.
attendees·description은 파싱 단계 폐기 — 저장 컬럼 자체가 없다 (§4.5).

### 11.4. 쿼터 예산
베타 100명 × 2.5 일정/일 = 250 활성 일정
경로 재조회는 §8.2의 5분·3분 구간만: 일정당 ≈ 24회 → 6,000/일
− input_hash 동일 시 생략         −30%
− (출발 격자, 도착 격자, 5분 버킷) 캐시 −25%
→ ≈ 3,200/일. 환경 API는 격자 공유 캐시로 ≈ 400/일.
계약 전 확인: 무료 쿼터 · QPS · 결과 저장 허용 여부 · 로고/출처 표기 (PRD §22)

### 11.5. 장애 시 저하 매트릭스
실패           동작                                      사용자에게 보이는 것
경로 API       마지막 성공 경로 + 교통 버퍼 2배. 20분 지속 시 직선거리     "최신 교통 정보를 가져오지 못했어요 · 12:31 기준"
보수 추정
환경 API       웰니스 행동·WIS 생략. 시간 계획 정상                 웰니스 카드 미노출. 오류 문구 없음
캘린더          마지막 스냅샷 + 지수 백오프. 내부 일정 무영향             설정에만 동기화 시각
IdP          재시도 안내(PRD §10.1). 유효 세션은 로그인 강제하지 않음   로그인 화면 오류 배너
FCM          로컬 알림 폴백 (TR-07)                        차이 없음
DB 쓰기        클라이언트 오프라인 큐 + 백오프 재전송                  낙관적 UI · 지속 시 배너 1회

## 12. API 명세
경로 구조는 팀 초안 §8을 기준으로 하고, 계약 규약(멱등성·시간 형식·오류 형태)을 그 위에 얹는다. 규약 셋 — 모든 쓰기는
멱등성 키를 받고, 모든 시각은 오프셋 포함 ISO-8601이며, 계획은 항상 근거와 함께 반환된다.
### 12.1. 엔드포인트
메서드 경로                                         설명                                요구사항
POST /auth/login · /auth/refresh ·             소셜 로그인 → JWT · 갱신 · 로그아웃          AUTH-01~04
/auth/logout

GET    /me/bootstrap                          설정·파라미터·장소·준비 항목·오늘 계획 일괄         TR-06
PATCH    /me/settings       · /me/permissions   설정·권한 상태                          SET-01/03, WELL-06
DELET    /me                                    탈퇴 — 하드 삭제                        AUTH-04, DATA-01
E
CRUD     /places                                주요 장소                             SET-01
CRUD     /prep-items                            맞춤 준비 항목. 민감 항목 lock_alias 포함     ONB-01, SET-02
POST/    /calendar/connections        ·         외부 캘린더 연결·수동 동기화                  CAL-02
GET/D    /calendar/sync
EL
GET    /events · /events/next                 일정 목록 · 다음 일정+계획 요약               CAL-01
POST/    /events · /events/{id}                 생성( source=map_search 시 경로 포함)·   CAL-01/03/05
PATCH                                           수정·삭제
/DEL
POST    /events/{id}/review                    분류 확인 응답 → 재학습 신호                 CAL-04
POST    /events/{id}/plan/recalculate          강제 재계산                            PLAN-04
GET    /plans/{id}    ·                       계획 + 트레이스 + 체크리스트 + degraded      PLAN-02/03/05
/events/{id}/plans/latest

GET/P   /plans/{id}/routes       ·             경로 후보 3종 · 선택(재계산 동반)             MAP-02/03
OST    /routes/select

POST    /plans/{id}/actions                    행동 이벤트 배치. 오프라인 큐의 도착지. 갱신된       TR-03, REPORT-01/02
계획을 응답에 동봉
GET    /notifications/today                   당일 알림 로그 (시간+웰니스)                 NOTI-05
GET    /summary/daily?date=                   일일 마무리 카드                         WELL-05
POST    /plans/{id}/personalization/rev        보정 되돌리기 + 표본 영구 제외                §6.3
ert

DELET    /personalization                       개인화 초기화 (로그 유지)                   DATA-02
E

### 12.2. 계획 응답 (발췌)
{
"revisionNo": 3, "engineVer": "2.1.0", "state": "NOTIFIED", "feasible": true,
"prepStartAt": "2026-08-16T12:25:00+09:00",
"departAt": "2026-08-16T13:10:00+09:00", "etaAt": "2026-08-16T13:50:00+09:00",
"trace": [
{"label":"개인 준비 시간","minutes":35,"source":"model","adjusted":true,
최근 8회 기록 기준, 초기 설정보다 +5분"},
"reason":"
{"label":"개인 루틴","minutes":10,"source":"prepItem","adjusted":false,
"reason":"렌즈·화장 (등록한 루틴)"},
{"label":"이동 시간","minutes":42,"source":"provider","adjusted":false,"reason":"외부 지도 API 기준"}
],
"checklist": [
{"label":"영양제","origin":"user","kind":"consume","state":"pending","private":false},
{"label":"선크림","origin":"user","kind":"carry","state":"pending",
"reason":"자외선 높음 · 야외 45분"},
{"label":"물·텀블러","origin":"wellness","kind":"carry","state":"pending",
"reason":"체감온도 높음"}
],
"wellness": {"wis": 72, "wisVer": "w1", "actionsShown": 2, "eventArmed": true},
"degraded": []
}

trace · checklist 는 정렬하지 않는다. 화면 순서는 PRD §31 미결(웰니스 카드 우선순위)이므로 클라이언트가 결정하고, 확정 시 앱만 바꾼다.
private:true 항목은 잠금화면·푸시에서 lock_alias ("개인 준비")로 치환된다(TR-10).

### 12.3. 공통 규약
항목                       규약
인증                        Authorization: Bearer · 모든 자원은 토큰의 user_id로 행 수준 필터
멱등성                      모든 POST/PUT에 Idempotency-Key . 24시간 내 동일 키 → 이전 응답 재생
시각                       ISO-8601 오프셋 필수 (TR-02)
오류                        {"error":{"code":"...","retryable":true}} · 타 사용자 자원은 404

버전                        X-App-Version → 최소 지원 미만 426

## 13. 트랜잭션 · 동시성 · 복구
팀 초안이 Ch.17~19에서 요구한 것을 구체화한다. 경합은 세 곳에서 난다 — 알림 발송 vs 상태 입력, 재계산 vs 상태 입력,
캘린더 동기화 vs 사용자 수정. 각각에 트랜잭션 경계와 승자 규칙을 정한다.
### 13.1. 트랜잭션 경계
작업                            하나의 트랜잭션                                         격리·규칙
계획 생성·재계산                     새 plan_revision + plan_context + checklist 투영 + 이전
이전 리비전은 유지(감사·재현). event 행에
리비전 superseded_by + 알림 재예약                       낙관적 잠금 불필요 — 리비전 자체가
버전이다
상태 입력                         plan_action_log INSERT + state 전이 + 예약 알림 취소 마킹 client_event_id UNIQUE가 중복 흡수.
state 전이는 WHERE state = 기대값 CAS
— 지오펜스와 수동 입력이 동시에 와도 한
번만 전이
알림 발송                         notification INSERT(dedup_key UNIQUE) → 커밋 → FCM 아웃박스 순서: INSERT 실패 = 이미 발송됨
전송 → sent_at 갱신                                  → 스킵. FCM 실패 = 행은 있고 sent_at NULL
→ 재시도 대상
캘린더 동기화                       일정 단위 upsert (ext_uid 기준)                        사용자가 수정한 필드(place_need_by='user')
는 동기화가 덮지 않는다 — 사용자 지정 항상
우선 (CAL-03)
탈퇴                            users 삭제 → CASCADE 전파                            동기 실행. 실패 시 전체 롤백 — 부분 삭제
상태를 만들지 않는다

### 13.2. 동시성 제어
워커 경합 FOR UPDATE SKIP LOCKED — 워커를 늘려도 같은 계획을 두 번 처리하지 않는다
알림 vs 상태 발송 직전 state 재확인. 발송 트랜잭션과 상태 입력이 교차하면
dedup_key는 남지만 전송 전 취소 마킹을 이긴 쪽이 최종 승자 — 최악의 경우도
"취소됐어야 할 알림 1건"이며, 잘못된 시각의 알림은 아니다
재계산 경합 같은 event에 동시 재계산 → UNIQUE(event_id, revision_no) 충돌 →
진 쪽은 새 리비전을 읽고 종료 (재시도 불필요)

### 13.3. 복구
재시도 대상      sent_at IS NULL알림(5분 백오프 3회) · 실패한 캘린더 동기화(지수 백오프)
멱등 재실행      틱 루프는 어느 시점에 죽어도 재시작 시 next_eval_at 기준으로 이어 달린다
— 진행 상태를 메모리에 두지 않는 것이 복구 전략의 전부다
백업          일 1회 pg_dump → 오브젝트 스토리지 · 복구 절차 문서화 · 분기 1회 복원 리허설
클라이언트       오프라인 큐는 Drift에 영속화. 앱 킬·재시작 후에도 재전송 (TR-03이 중복 흡수)

## 14. 프라이버시 엔지니어링
PRD §20의 원칙을 지키지 않는 것이 어렵도록 구조로 만든다. v2.0의 새 과제는 맞춤 준비 항목이다 — 복용약·기호 품목은
생활과 건강 성향을 드러내는 정보이며(PRD §20.3), 잠금화면과 푸시가 새는 경로다.
### 14.1. 구조적 강제
PRD 원칙                     강제 방법
일정 참석자·메모 미수집              허용 목록 파싱 — 저장 스키마에 컬럼이 없다
이동 경로 미저장                  지오펜스 이벤트에 좌표 컬럼이 없다. (kind, ts, confidence)만
민감 항목 잠금화면 일반화             푸시 payload 생성 시 sensitive=true 면 lock_alias 로 치환. 원문은 앱 잠금 해제 후에만
민감 항목 비추천                  from_chip ∧ sensitive 조합을 서버 검증이 거부 — 추천 칩 목록에 민감 품목이 물리적으로 없다

건강 판단 미수행                  준비 항목은 label·kind·extra_min만 저장. 성분·용량·효능 필드가 없다 — 판단할 데이터 자체가 없다
로그 차단                      로거 필드 차단 목록(좌표·제목·항목 label) + CI 검사

TR-10 · 민감 준비 항목은 3중 경계로 격리한다
① 표시 경계 — 잠금화면·워치·푸시에는 lock_alias("개인 준비")만. ② 추천 경계 — 앱은 민감·규제 품목을 먼저 제안하지 않는다
(PRD §1.1). 사용자가 직접 입력한 경우에만 비공개 루틴으로 처리. ③ 집계 경계 — 리포트·일일 요약·지표 이벤트의 입력에서 민감
항목 label을 원천 제외한다(PRD §14.8). 세 경계 모두 코드 리뷰 체크 항목이다.

### 14.2. 권한 요청 시점 (PRD §11.4)
로그인·약관     → 첫 실행 (회원 전용이므로 유일한 선행 조건)
캘린더         → 연동 기능 진입 시 · "다음 일정과 장소 자동 인식" 설명 후
위치("사용 중") → 첫 목적지 입력 시
알림           → 첫 준비 계획 생성 후
위치("항상")    → 출발·도착 자동 확인 켤 때 별도 화면 명시 동의
웰니스 이벤트 알림 → 관심 항목 설정에서 별도 토글 (기본 시간 알림과 분리, PRD §13)
거부 후 재안내는 기능 진입점에서 1회만.

### 14.3. 암호화·삭제
대상                  처리
전송                  TLS 1.3 · 피닝은 P1 (베타 롤백 리스크 우선)
서버 저장               디스크 암호화 + 장소 좌표·준비 항목 label·캘린더/IdP 토큰은 애플리케이션 레벨 AES-GCM (키는 시크릿 매니저)
기기 저장               토큰·민감 캐시 Keychain/EncryptedSharedPreferences · 로그아웃 시 소거 (PRD §23.4)
삭제 3단               §4.5 — 로그아웃/개인화 초기화/탈퇴의 대상 분리

## 15. 성능 예산과 SLO
항목                            목표         측정 지점       근거
홈 첫 일정 카드                   ≤ 400ms     앱(콜드 제외)     로컬 캐시 우선 렌더
계획 생성 전체                    p95 ≤ 5s      서버 API     PRD §23.1 · 외부 경로 호출 포함
WIS + 행동 제안                 p95 ≤ 2s      서버 API     PRD §23.1 명시 (환경 데이터 준비 시)
재계산(캐시 적중)                p95 ≤ 400ms     서버 API     경로 변경 즉시 재계산
엔진 순수 계산                     ≤ 5ms       단위 테스트      Plan+Wellness 합산, I/O 제외
스케줄러 틱                        ≤ 15s      서버 로그       초과 시 §2.3 확장 신호
알림 발송 지연                   p95 ≤ 30s    계획 시각 대비     정밀 시각은 로컬 알림 담당 (TR-07)
액션 배치 업로드                 p95 ≤ 800ms     서버 API     100건 기준
배터리 추가 소모                   ≤ 2%/일        실기기        센서 제거로 v1.0(3%)보다 강화. 지오펜스가 주 소비원
콜드 스타트                       ≤ 2.0s     중급 Android   로그인 검사 포함
가용성 목표 99.0%(베타·단일 VM). 계산 실패 시에도 앱은 마지막 계획을 보여주므로 체감 가용성은 이보다 높다.

## 16. 관측성과 지표 수집
PRD §24의 지표는 이벤트 스키마 없이는 하나도 측정되지 않는다
웰니스 지표 4종(완료율·반응률·적합률·커버리지)이 새로 들어왔다. 아래 이벤트는 P0 필수 구현이며, 각 지표는 이 이벤트의
조합으로만 산출한다.

### 16.1. 제품 이벤트 스키마
이벤트                                      주요 속성                                          산출 지표 (PRD §24)
auth_completed      /                   provider, is_new                               로그인·약관 완료율
consent_done

prep_item_registered                    kind, from_chip, sensitive(bool만), skipped     맞춤 항목 설정률·건너뛰기율
plan_created    /                       revision, reason, delta_min, degraded[]        첫 계획 생성률 · 계획 변경
plan_recomputed

notification_sent       /               class, slot, dedup_key, action, latency        알림 반응률 · 극한 알림 비율
reacted

action_recorded                         type, source, delta_vs_plan_min                준비 시작 지연률 · 출발 편차
event_outcome                           outcome, margin, snooze, tight_self_report     북극성 · 정시/촉박/조기 도착
prep_model_adjusted             /       knob(prep|buffer|lead), from, to, cause        예측 오차 추이 · 되돌림률
reverted

wellness_action_shown               /   action_key, wis, origin, result                행동 완료율 · 커버리지
resolved

wellness_push_sent          /           action_key, interval_set,                      이벤트 반응률 · 재도포 완료율
reacted                                 result(done|later|stop)
wellness_rating                         useful(bool), context_missing(bool)            적합률 · 불필요 평가율
checklist_resolved                      origin, kind, state                            맞춤 항목 체크 완료율
daily_card_viewed                       template, dwl_level, dismissed_sec, disabled   확인률 · 비활성화율
geofence_decision                       kind, confidence, auto, corrected              자동 확인 성공률·수정률
permission_result       /               kind, granted / from, to, conf                 권한 허용·철회 · 오분류율
classify_corrected

### 16.2. 북극성과 웰니스 보조 지표 산출
북극성 (PRD §24.1) — "늦지 않고 여유 있게 도착한 주간 일정 수"
ok = outcome='on_time' ∧ 극한알림 ≤ 1 ∧ |Δdepart| ≤ DEPART_TOL
∧ tight_self_report ≠ true ∧ margin ≤ EARLY_MIN
웰니스 보조 핵심 (PRD §4.4 · §24.4)
행동 완료율 = resolved(done) / shown(유효)
이벤트 반응률 = reacted(done|later) / push_sent
적합률     = rating(useful) / rating(수집)
커버리지 = WIS·행동 생성된 야외 일정 / 관리된 야외 일정
임계값 4종은 전부 원격 설정 — 쿼리 수정 없이 재집계 가능해야 한다 (TR-06).

저장은 events 테이블(JSONB, 날짜 파티션) + 주간 집계 뷰. 별도 분석 인프라 미도입. 운영 알림 셋: 틱 지연 5분↑ · 경로 API 성공률 90%↓ · 푸시 실패율
20%↑.

## 17. 테스트 전략
화면을 눌러서는 확인할 수 없는 로직이 몰려 있다 — 3주 뒤에 드러나는 학습 편향, 새벽에 한 번 밀리는 스케줄러, 그리고 의료
조언으로 읽히는 문장 하나. 테스트는 이 세 곳을 겨냥한다.
### 17.1. 우선순위
계층                           비중         대상
골든 테스트                       최우선        Plan Engine · Wellness Engine · 보정 라우팅 — 입력 JSON → 출력 JSON 고정
속성 테스트                       높음         보정 가드레일 불변식 · WIS 경계 조건
시뮬레이션                        높음         스케줄러·이중 알림 예산을 가상 시계로 압축 실행
실기기 실측                       필수         지오펜스 판정·배터리 (PRD §26.5 대비)
카피 린트                        필수         의료 경계 — 아래 §17.5

### 17.2. 골든 테스트
tests/golden/
01_basic_arrive_by.json                계산 예시 고정
PRD §12.4
02_rain_adds_prep.json        강수 → 추가 준비 + 우산 체크리스트
03_routine_adds_time.json     시간 루틴 10분 → 준비 시작 10분 앞당김 (PLAN-05)
04_carry_no_time.json         챙기기 항목 → 시간 불변 + 체크리스트만
05_depart_at_anchor.json      지도 검색 저장(출발 기준) 정방향 계산
06_constraint_infeasible.json 시각 제약 충돌 → feasible=false (TR-04)
07_no_env_data.json           환경 부재 → WIS 생략 · 시간 정상 (PRD §23.2)
08_wis_bands.json             U·P·T·O 조합별 0~39/40~69/70~100 구간 판정
09_wellness_merge.json        사용자 선크림 + 웰니스 선크림 → 1개 병합 (§5.4)
10_dst_boundary.json          시간대 경계 (TR-02)
상수 변경 → 골든 파괴 → 의도한 변경이면 engine_ver/wis_ver 올리고 스냅샷 갱신.
이 절차가 "가중치를 몰래 바꾸는 일"을 구조적으로 막는다 — PRD §16.9의 실험 비교 전제.

### 17.3. 불변식 (속성 테스트)
① P ∈ [10, seed×2] · 1회 변화 ≤ 15분 (콜드 스타트 예외)
② 교통 지연만 있는 시퀀스에서 P는 변하지 않는다           ← 원인 분리의 핵심 검증 (TR-05)
③ 전부 on_time인 시퀀스에서 P는 발산하지 않는다
④ WIS는 입력 정규화값에 단조 — 자외선이 오르는데 점수가 내리는 일이 없다
⑤ 동일 입력 재실행 시 계획·점수·체크리스트 완전 동일 (순수성)
⑥ WIS < 70 이면 웰니스 푸시 후보가 절대 생성되지 않는다 (TR-11)

### 17.4. 시뮬레이션과 실측
가상 시계 하루 재생: 일정 200건 · 교통 지연·환경 변화 랜덤 · 상태 입력 랜덤
검증: 시간 푸시 ≤ 3/일정 · 웰니스 푸시 ≤ 1/일정 · dedup 중복 0 ·
상태 입력 후 잔존 예약 0 · 모든 계획 CLOSED 도달 · '오늘은 그만' 후 당일 0건
실기기(3기종): 귀가 왕복 지오펜스 — 자동 확정률 ≥ 70% · 오판 ≤ 10% · 배터리 ≤ 2%/일

### 17.5. 의료 경계 카피 린트
출시 기준(PRD §30)의 "콘텐츠 검토 통과"를 CI로 앞당긴다
사용자에게 나가는 모든 문구는 템플릿 테이블에만 존재한다(TR-09). CI가 두 가지를 검사한다 — ① 템플릿 외 문자열이 알림·
카드 렌더 경로에 유입되는지(정적 검사), ② 템플릿 텍스트에 금지 패턴(진단·치료·복용량·효능 보장·"피부에 좋은" 류)이 있는지
(사전 기반 린트). 통과 목록은 콘텐츠 검토 회의가 관리하고, 코드는 그 목록 밖의 문장을 만들 수 없다.

## 18. 마일스톤과 개발 순서
기준은 하나다. 가장 늦게 알게 되는 것을 가장 먼저 만든다. 개인화 정확도와 웰니스 알림의 피로도는 데이터가 쌓여야만
보인다. 알림 → 행동 기록 → 보정의 순환(M2)이 닫히는 날부터 모든 주가 데이터를 쌓는 주가 된다.
단계         기간     산출물                                       완료 판정
M0        1주     스키마 전체 · 소셜 로그인+JWT · 약관 동의 · 캘린더 읽기      로그인 → 일정 유입 → 스텁 경로로 계획 생성
기반               · Provider 스텁 · CI
M1        1.5주   Plan Engine + 트레이스 + 체크리스트 합성 · 맞춤 준비     PRD §12.4 예시 골든 재현 · 루틴 등록이 준비 시각을
엔진               항목 CRUD · 골든 01~06 · 실제 경로/환경 제공자 1종      실제로 움직인다
M2        2주     오케스트레이터 · 시간 알림 3종 + 로그 · 행동 이벤트 +        알림 → 원탭 기록 → 다음 계획 보정 한 바퀴
순환               오프라인 큐 · 원인 분리 보정
M3        1.5주   Wellness Engine(WIS·행동 매핑) · 이벤트 스케줄러 ·   야외 일정에서 행동 ≤3 노출 · 조건 충족 시 재도포 푸시
웰니스               템플릿 카피 + 린트 · 골든 07~09                    1회
M4        1.5주   지도 화면(기본 경로·캘린더 저장) · 지오펜스 출발·도착 ·        PRD §30 출시 기준 전 항목
완결               일일 마무리 카드 · 설정·삭제 3단
M5        1주     저하 매트릭스 검증 · 지표 대시보드 · 시뮬레이션 · 실기기        SLO 측정 가능 · 예산 위반 0 · 베타 배포
경화               3기종 · 콘텐츠 검토
총 8.5주. 사전 설문(PRD §26.1)은 M0와 병행 — 결과가 가설을 지지하지 않으면 M2 알림 설계를 재검토. 지도 제공자(D1)가 M1까지 미확정이면 스텁으로
M3까지 진행. 웰니스 알림 실험(PRD §26.4 A/B/C)은 M3 완료 직후 프로토타입으로 실행해 베타 전에 C군(이벤트 푸시)의 유지 여부를 결정한다.
병렬 배분
FE      로그인·스캐폴딩 → M1 준비항목 UI → M2 홈·알림·오프라인 큐 → M4 지도·캘린더·카드
M0
BE      스키마·인증 → M1 엔진 → M2 오케스트레이터·보정 → M3 웰니스 엔진 → M5 저하·지표
M0
AI/D M1 골든 픽스처 → M2 라우팅·불변식 → M3 WIS 정규화·실험 설계 → M5 대시보드
QA   M1 골든 리뷰 → M3 카피 린트 사전 구축 → M4 지오펜스 실측 → M5 시뮬레이션

## 19. 기술 위험과 대응
#    위험                      영향   대응
T1   지도 제공자 확정 지연            높음   인터페이스+스텁으로 M3까지 진행. 기본 경로만 쓰므로 교체 표면이 v1.0보다 작다
T    환경 데이터 결측·부정확이 웰니스      높음   부재 시 생략(§11.5) · 기준시각·제공자 표기(PRD 위험 9) · 커버리지 지표로 추적
## 2. 신뢰를 깎는다
T    웰니스 푸시 피로 → 알림 전체 차단    높음   동의×WIS×주기×노출 4중 게이트(TR-11) · 일정당 1회 · '오늘은 그만' 즉시 반영 · 해제율
## 3. 가드레일 초과 시 조건 자동 축소(PRD §26.6)
T    웰니스 문구의 의료 조언 오해        높음   템플릿 전용(TR-09) + CI 카피 린트(§17.5) + 콘텐츠 검토를 출시 기준으로
T5 원인 오귀속 보정 — 교통 지연이 준비     높음   라우팅 분리(TR-05) · 불변식 ② 상시 검증 · 되돌리기+표본 영구 제외
시간을 늘림
T 푸시 지연으로 준비 알림이 늦음          높음   로컬 알림 이중화(TR-07) · 발송 지연 p95 지표
T7 지오펜스 오판이 학습 오염            중간   confidence 게이트(§6.1) · 수정률 가드레일 · 사용자 수정 항상 승리
T iOS 리전 한도 조용한 초과           중간   활성 1건·2리전(TR-08) · 등록 실패 계측 · 수동 폴백 상시
T 시간대·DST 버그                 중간   TR-02 규약 · 골든 10 · 종일 일정 제외
T1 민감 준비 항목 노출 (잠금화면·푸시·     중간   3중 경계(TR-10) · 렌더 경로 검사 · 입력 후 삭제율 추적
## 0. 리포트)
T1 소셜 로그인 심사 리스크 (Apple 병행   중간   D2 결정 시 iOS 요건 반영 · IdP 어댑터 구조로 추가 비용 최소화
## 1. 요건)
T1 오프라인 이벤트 유실·중복            중간   client_event_id UNIQUE(TR-03) · 큐 영속화 · duplicated 모니터링
T1 단일 VM 장애                  중간   로컬 알림 2건 방어 · 일 1회 덤프 · 재시작 정책 · next_eval_at 기반 무상태 복구(§13.3)

## 20. 미결 기술 결정
ID 결정할 것                     필요 시점    막히면              결정 방법
D1 지도·경로 제공자 (+ leg 지하/지상 구분   M1 말   M3까지              경로 품질·쿼터·약관·outdoor 추정 가능성 4축 (PRD §31)
여부)                                스텁
D2 소셜 로그인 IdP 1차 지원          M0 착수 전 M0 차단              타깃 사용 비율 + iOS Apple 병행 요건 (PRD §31)
D3 WIS 가중치·구간·이벤트 임계            M5     기본값              베타 A/B (PRD §31) — 원격 설정이라 배포 불필요 (TR-06)
D4 선크림 이벤트 기본 제공 여부·일정당 최대 M3 실험 후 기본 1회                PRD §26.4 C군 실험 + 페르소나 B 인터뷰
횟수
D5 DWL 표시 방식 — 숫자·등급·문장         M4   수준형 문장             이해도·불안감 테스트 (PRD §31)
D6 P0 웰니스 항목 최종 범위 (자외선·        M3    전 항목              데이터 품질 vs 수요 (PRD §31)
미세먼지·기온·강수·수분)                     수집,
노출만 좁힘
D7 지오펜스 반경·체류 최종값               M4     기본값              실기기 이동 테스트
D8 보정 α·상한 확정                   M5     기본값              베타 실측 (PRD §16.2)
D9 알림 피로 중단 기준 (해제율·불필요         M5    수동 판단             베타 가드레일 설정 (PRD §31)
평가율 임계)
D10 탈퇴 시 연쇄 삭제 vs 익명화 — 항목별     M4    하드 삭제             법무 검토 (팀 초안 §19)
최종 정책
D11 fromChip(추천 칩 선택 여부) USER_PREP_RULE M0   저장 안 함(요청       ERD v3에 컬럼 없음 — PRD §1.1 위험 12
컬럼 신설 필요 여부                        스키마   검증만, TR-10)         칩·직접입력 구분이 실존 요구인지 백엔드B 협의
웰니스 카드 화면 우선순위(PRD §31)는 UI 계층 결정이며 서버를 막지 않는다 — trace·checklist는 정렬 없이 태그만 싣는다(§12.2). 확정 시 앱만 바꾼다.

A 파라미터 레지스트리
전부 추정치다. 코드 상수가 아니라 engine_config 테이블에 있으며, 변경은 engine_ver / wis_ver 를 올리고 감사
로그를 남긴다(TR-06). PRD §31이 베타 데이터로 확정하기로 한 값들이 대부분이다.
A.1 계획 엔진 · 보정
파라미터                                    기본값            확정 시점      의미
ARRIVAL_BUFFER_MIN                      10            사용자 설정     도착 여유
TRAFFIC_BUFFER_MIN                       5            개인화 대상     초기값. §6.2에서 원인 분리 갱신
RAIN_EXTRA_PREP_MIN                      5              M5       강수 시 준비 가산
SILENT_SHIFT_MIN    /                  2/5              M5       리비전 생략 / 돌발 알림 경계
MATERIAL_SHIFT_MIN

TIGHT_MIN    / EARLY_MIN               5 / 30         M5 베타      촉박·과도 조기 판정 (북극성 입력)
PREP_EMA_ALPHA                          0.30          M5 D8      PRD §16.2 명시 초기값
LATE_WEIGHT / EARLY_WEIGHT            1.5 / 0.7         M5       비대칭 신호 가중 (§6.2)
MAX_STEP_MIN / COLD_STEP_MIN           15 / 20        M5 D8      1회 보정 상한(PRD 명시) / 첫 실패 예외
PREP_FLOOR_MIN / PREP_CEIL_RATIO      10 / 2.0          M5       절대 하한 / 시드 대비 상한
SNOOZE_STEP_MIN / SNOOZE_CAP_MIN        2 / 10          M5       스누즈 → notif_lead 가산
CLASSIFY_MIN_CONF                       0.70            M2       미만 시 1회 확인 (CAL-04)

A.2 웰니스 엔진
파라미터                                      기본값          확정 시점      의미
WIS_W_UV / PM / TEMP / OUTDOOR     .35/.25/.20/.20   베타 A/B D3   PRD §14.3 가중치
WIS_INTEREST_BOOST_MAX                     1.25         베타       관심 항목 보정 상한 (M)
OUTDOOR_CAP_MIN                            120        고정(PRD)    O 정규화 상한
WIS_BAND_CARD / EVENT                   40 / 70       베타 D3      준비 카드 / 이벤트 후보 구간 경계
WELLNESS_EVENT_PER_SCHEDULE                  1       M3 실험 D4    일정당 웰니스 푸시 상한 (PRD §13.5)
WELLNESS_IGNORE_BACKOFF_N                    2          베타       연속 무시 → 빈도 하향 (PRD §14.7)
UV_HIGH / PM_BAD / RAIN_LIGHT /     6 / 나쁨 / 30%         M3      양자화 경계 (§7.2)
RAIN_HEAVY                                / 60%
RLS_W_DP / DD / E                     .45/.35/.20      베타        PRD §14.4 가중치
DWL_W_WIS / RLS                        0.6 / 0.4       베타        PRD §14.5
DWL_LEVELS                              [40, 70]      M4 D5      낮음|보통|높음 경계

A.3 오케스트레이션 · 지오펜스
파라미터                                  기본값         확정 시점     의미
TICK_INTERVAL_SEC                       30         M5      스케줄러 주기
TIME_NOTIF_BUDGET                        3       고정(PRD)   일정당 시간 알림
ACTIVE_WINDOW_LEAD_MIN                  30         M4      활성 창 개시
UNRESOLVED_AFTER_MIN                    30         M5      도착 판정 실패 전환
GEOFENCE_ORIGIN_R_M / DEST_R_M   130 / 100~200   M4 D7     리전 반경 (목적지 유형별)
DWELL_SEC / AUTO_CONF              90 / 0.60     M4 D7     체류 검증 / 자동 확정 신뢰도
DEPART_TOL_MIN                          10       M5 베타     북극성 "계획 범위 내 출발" 허용 폭

B 요구사항 추적 매트릭스
PRD v0.4.3 기능 요구사항 41건의 구현 위치와 검증 방법. 구현 절이 비어 있는 행이 없어야 이 TRD가 PRD를 다 덮은 것이다.

PRD ID       우 TRD 구현 절                                 검증
선
AUTH-01~04   P §10 인증·수명주기 · §12.3 행 수준 접근              E2E 로그인·세션·탈퇴 · 교차 사용자 404 테스트
ONB-01       P   §4.3 user_prep_item · §12.1 /prep-items 등록·건너뛰기 E2E · prep_item_registered 지표
CAL-01/02    P   §4.2 event · §11.3 동기화                 CRUD E2E · 증분 반영
CAL-03       P   §4.2 place_need_by · §13.1 동기화         사용자 지정 우선 단위 테스트
## 0. 불침범
CAL-04       P   §12.1 /events/review · classify_conf   분류 픽스처 · classify_corrected
CAL-05       P   §12.1 source=map_search · anchor       E2E 지도 검색 → 저장 → 계획 · 골든 05
PLAN-01      P   §6.2 시드·콜드 스타트                         골든 07 유사 픽스처
PLAN-02/03   P   §5.2~5.3 · plan_context                골든 01 · 트레이스 없는 계획 반환 불가(스키마)
PLAN-04      P   §12.1 recalculate·routes/select        수정 후 재계산 E2E
PLAN-05      P   §5.4 체크리스트 합성                          골든 03·04 — 루틴은 시간, 준비물은 목록만
MAP-01~03    P   §11.1 RouteProvider · §12.1 /routes    스텁·실제 이중 테스트 · 3종 경로 비교
MAP-04       P   UI 계층 (§18 지도 범위 확정 반영)                프로토타입 테스트 (PRD §26.3)
NOTI-01~03   P   §8.1~8.3 상태 기계·실질 변화                   시뮬레이션 — 예산 위반 0
NOTI-04      P   §7.4 이벤트 스케줄러 (TR-11)                  불변식 ⑥ · 시뮬레이션 '그만' 후 0건
NOTI-05      P   §12.1 /notifications/today             E2E — 시간·웰니스 통합 로그
WELL-01      P   §11.2 환경 데이터 · §7.2 정규화                제공자 픽스처 · 결측 경로
WELL-02      P   §7.1~7.3 WIS                           골든 08 · 불변식 ④
WELL-03      P   §7.3 행동 매핑 · §5.4 병합                   골든 09 · 최대 3개 검증
WELL-04      P   §7.4 재도포 게이트                           조건 조합 단위 테스트 · M3 실험
WELL-05      P   §7.5 일일 카드 · daily_summary             데이터 부족 시 미노출 테스트
WELL-06      P   §12.1 /me/settings · user_interest     토글 반영 E2E

MODEL-01       P   §6.1~6.2 원인 분리 보정                   불변식 ①~③ — 특히 ② 교통≠준비
MODEL-02       P   §6.4 유형별 분리                         표본 미달 폴백 테스트
REPORT-01      P   §4.3 로그 · outcome 판정                이벤트 조인 쿼리 검증
REPORT-02      P   §4.3 wellness_action                원탭 기록 E2E
REPORT-03      P   §16 주간 집계 뷰                         —
SET-01         P   §4.2 user_place (암호화)               CRUD·개별 삭제
SET-02         P   §4.3 user_prep_item · TR-10         민감 항목 잠금화면 치환 테스트
SET-03         P   §4.2 user_interest · §7.4 주기        설정 변경 → 게이트 반영
DATA-01        P   §10.2 탈퇴 · §13.1 CASCADE            삭제 후 잔존 0 (동의 이력 제외)
DATA-02        P   §6.3 · §4.5 삭제 3단                   초기화 후 로그 보존 확인

본 TRD가 신설한 기술 요구사항
ID         요구사항                                       배경
TR-01      계획·웰니스 판단의 권위는 서버 단독                       앱 종료 상태의 재계산·푸시 (PRD §16.5~16.6)
TR-02      시간대 규약 5항 · 기기 시계 불신                       계획이 몇 시간 어긋나는 최다 원인
TR-03      행동 이벤트는 클라이언트 멱등 키 필수                      오프라인 재전송의 학습 오염 차단
TR-04      엔진은 사용자 등록 사실을 판단하지 않고, 제약 충돌은             PRD §14.8 의료·소비 판단 금지의 코드 구현
feasible=false로 반환
TR-05      하나의 관측은 하나의 손잡이만 조정 (원인 분리                 PRD §16.2 v0.4.3 명문화 — 오귀속 보정 차단
라우팅)
TR-06      모든 상수는 원격 설정 · 변경 시 버전 증가                  PRD §31 — WIS 가중치 등 베타 확정 항목
TR-07      준비·출발 알림은 로컬 알림으로 이중화                      푸시는 전송 시각을 보장하지 않는다
TR-08      지오펜스는 활성 계획 1건·리전 2개 제한                    iOS 20개 한도의 조용한 실패
TR-09      웰니스 카피에 생성 모델 금지 — 승인 템플릿 전용               PRD §14.8·§30 의료 경계를 확률로 지킬 수 없다
TR-10      민감 준비 항목의 3중 경계 (표시·추천·집계)                 PRD §1.1·§14.8·§20.3 · 위험 12
TR-11      웰니스 푸시 4중 게이트 — 동의×점수×주기×노출                PRD §12.7 발사 조건의 구조적 강제

늦지 않게, 서두르지 않게.
