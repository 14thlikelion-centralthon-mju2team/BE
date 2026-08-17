-- Ensom 전환: 재설계 대상 6개 영역(users/auth/consent/place/calendar/event) 정리
-- 근거: MIGRATION.md §2 재설계 판정, ERD v3.1, TRD v4.0 §4.3~4.5, API 명세서 v5.0
--
-- 이 워크스페이스는 실제 가입 사용자·연동 데이터가 없는 해커톤 개발 DB이므로(팀 확인 완료),
-- old→new 컬럼 매핑 데이터 이관 없이 드롭 후 재생성한다. place_visits/user_consents/
-- calendar_connections/user_events/fcm_tokens는 ERD v3.1에서 이름·구조가 전부 달라
-- ALTER 대상이 아니라 교체 대상이다.
--
-- users 테이블만 이름이 동일해 ALTER로 처리한다. provider/provider_uid/password_hash를
-- user_identity/user_credential로 옮기고(CASCADE로 의존 인덱스·제약도 함께 정리),
-- account_status 기반 생명주기로 archived_at을 대체한다(D10 — 탈퇴는 하드 삭제,
-- 익명화 배치 없음. 소프트 삭제 개념 자체가 새 모델엔 없다).
--
-- 폐기 대상 5개 패키지(checkin/gapcheck/state/routine/adjustment)의 테이블
-- (daily_checkins/gap_checks/actions/routines/routine_tasks/routine_runs/task_logs/
-- adjustments)과 v_daily_states 뷰는 TRD §3.1·§20 "부수 결정"에 따라 M5까지 존치 —
-- 여기서 건드리지 않는다. health_checkups도 대응 애플리케이션 코드가 없어 범위 밖.
-- idempotency_keys는 MIGRATION.md §2 "유지" 판정 그대로 둔다(별도 idempotency_record
-- 신설하지 않는다).

-- -----------------------------------------------------------------------------
-- 1. 교체 대상 테이블 드롭 (이름·구조가 ERD v3.1과 달라 ALTER 불가)
-- -----------------------------------------------------------------------------

drop table if exists place_visits cascade;         -- ERD v3.1에 대응 테이블 없음 (MIGRATION.md: PlaceVisit 폐기)
drop table if exists places cascade;                -- → user_place
drop table if exists user_consents cascade;         -- → user_consent (append-only 트리거도 함께 제거됨)
drop table if exists calendar_connections cascade;  -- → calendar_connection + calendar_source
drop table if exists user_events cascade;            -- → event
drop table if exists fcm_tokens cascade;             -- → push_device

-- -----------------------------------------------------------------------------
-- 2. users — 같은 테이블을 ALTER. CASCADE로 의존 제약·인덱스 자동 정리
-- -----------------------------------------------------------------------------

alter table users drop column provider cascade;       -- → user_identity.provider
alter table users drop column provider_uid cascade;   -- → user_identity.provider_uid
alter table users drop column password_hash cascade;  -- → user_credential.password_hash
alter table users drop column age_confirmed_at cascade; -- Ensom 범위에 없음 (Vium 전용 규제 확인)
alter table users drop column archived_at cascade;    -- → account_status/withdrawn_at (D10 하드 삭제 모델)

alter table users add column account_status text not null default 'active';
alter table users add column email_verified_at timestamptz;
alter table users add column withdrawn_at timestamptz;
alter table users add column deleted_at timestamptz;

alter table users add constraint ck_users_account_status
    check (account_status in ('active', 'withdrawn'));
alter table users add constraint ck_users_timezone
    check (length(timezone) between 1 and 128);
alter table users add constraint uq_users_email unique (email);
