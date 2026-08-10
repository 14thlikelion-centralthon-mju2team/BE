-- =============================================================
-- HQ(Vium) 스키마 v0.8  (PostgreSQL)
-- 기준: PRD v0.9 (2026-08-10, 개발 기준선)
-- 소유: Spring Boot / Flyway.  실제 반영 시 V1__init.sql 로 배치.
--
-- v0.7 → v0.8 변경
--   * user_events 신규 — PRD FR-010 "사용자 직접 일정"이 MVP Must인데 담을 표가 없었다.
--     캘린더 밀도 계산은 Google Calendar + user_events 를 합산한다.
--     title 저장 여부·보존 기간은 PRD Q-004 미결 — 동결 전 결정할 것.
--
-- v0.6 → v0.7 변경 (8/8 회의 결정)
--   1. users.provider     = 'email' | 'google'  (kakao 제외, 게스트 없음)
--      + password_hash (email 가입자만), + age_confirmed_at (만 14세 확인)
--   2. daily_checkins     신규 — 일일 최소 입력값 3개 + 휴식일 라벨
--   3. actions.est_minutes 추가 — 가용 시간 매칭에 필수
--   4. health_checkups    신규 — AAC 실측값 (자가보고 대체)
--   5. task_logs.status   에 'unknown' 추가 — "기억 안 남"
--   6. v_daily_states     휴식일 제외 + unknown을 분모에서 제외 + gray 제거(3색 확정)
-- =============================================================

create extension if not exists pgcrypto;


-- =============================================================
-- 1. 사용자 · 동의
-- =============================================================

create table users (
  id               uuid primary key default gen_random_uuid(),
  provider         text not null check (provider in ('email', 'google')),
  provider_uid     text not null,                   -- email 가입 시 이메일 주소, google 가입 시 sub
  email            text not null,
  password_hash    text,                            -- email 가입자만. google 가입자는 null
  nickname         text not null,
  timezone         text not null default 'Asia/Seoul',
  age_confirmed_at timestamptz,                     -- 만 14세 이상 확인 시각. 나이 자체는 저장하지 않는다
  archived_at      timestamptz,
  created_at       timestamptz not null default now(),
  unique (provider, provider_uid),
  -- 가입 수단과 인증 수단의 정합성을 DB에서 강제
  check (
    (provider = 'email'  and password_hash is not null) or
    (provider = 'google' and password_hash is null)
  )
);

create unique index idx_users_email_active
  on users (lower(email)) where archived_at is null;

-- append-only. 동의 철회도 agreed=false 새 행으로 남긴다.
create table user_consents (
  id             bigserial primary key,
  user_id        uuid not null references users(id) on delete cascade,
  consent_type   text not null check (consent_type in
                   ('terms', 'privacy', 'location', 'calendar', 'health_data', 'marketing')),
  policy_version text not null,
  agreed         boolean not null,
  recorded_at    timestamptz not null default now()
);

create index idx_user_consents_lookup
  on user_consents (user_id, consent_type, recorded_at desc);


-- =============================================================
-- 2. 장소
-- =============================================================

create table places (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references users(id) on delete cascade,
  label          text not null,
  lat            double precision not null,
  lng            double precision not null,
  radius_m       integer not null default 300 check (radius_m between 100 and 2000),
  kakao_place_id text,
  archived_at    timestamptz,
  created_at     timestamptz not null default now()
);

create index idx_places_active on places (user_id) where archived_at is null;

-- 지오펜스 진입/이탈 이벤트. user_id는 places 조인 없이 소유권을 막기 위한 비정규화.
create table place_visits (
  id         bigserial primary key,
  user_id    uuid not null references users(id) on delete cascade,
  place_id   uuid not null references places(id),
  entered_at timestamptz not null default now(),
  exited_at  timestamptz,
  check (exited_at is null or exited_at >= entered_at)
);

create index idx_place_visits_recent on place_visits (user_id, entered_at desc);


-- =============================================================
-- 3. 행동 라이브러리
-- =============================================================
-- 난이도 = (ladder_key, difficulty) 좌표. 조정은 같은 ladder_key 안에서만 움직인다.
-- est_minutes = daily_checkins.available_minutes 와 맞물리는 값.
--               "오늘 20분" → 5분 + 10분 + 3분 조합이 루틴 설계의 실제 동작이다.

create table actions (
  id          uuid primary key default gen_random_uuid(),
  category    text not null check (category in
                ('sleep', 'hydration', 'move', 'focus', 'meal')),
  ladder_key  text not null,
  difficulty  int  not null check (difficulty between 1 and 5),
  est_minutes int  not null check (est_minutes > 0),
  title       text not null,
  description text,
  unique (ladder_key, difficulty)
);

create index idx_actions_pick on actions (category, difficulty, est_minutes);


-- =============================================================
-- 4. 루틴 정의
-- =============================================================

create table routines (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references users(id) on delete cascade,
  place_id      uuid references places(id),         -- null = 시간 기반 루틴
  title         text not null,
  schedule_type text not null check (schedule_type in ('time', 'place')),
  rrule         text,
  anchor_time   time,
  archived_at   timestamptz,
  created_at    timestamptz not null default now(),
  check (
    (schedule_type = 'place' and place_id is not null) or
    (schedule_type = 'time'  and rrule is not null and anchor_time is not null)
  )
);

create index idx_routines_active on routines (user_id) where archived_at is null;

create table routine_tasks (
  id          uuid primary key default gen_random_uuid(),
  routine_id  uuid not null references routines(id) on delete cascade,
  action_id   uuid not null references actions(id), -- 현재 난이도. adjustments 트리거만 변경한다.
  order_no    int  not null,
  archived_at timestamptz,
  created_at  timestamptz not null default now()
);

create index idx_routine_tasks_order on routine_tasks (routine_id, order_no);


-- =============================================================
-- 5. 일일 체크인  ★ 신규
-- =============================================================
-- 8/8 확정 — 사용자에게 매일 받는 값은 딱 3개.
--   ① available_minutes  가용 시간 (직접 입력. 즉흥적이라 예측 불가)
--   ② condition          "추론 + 태그 정정" — 알고리즘이 먼저 추측하고 사용자는 맞다/아니다만
--   ③ focus_area         신경 쓰이는 부분 (선택형)
--
-- inferred / final 을 나누는 이유: 추론이 얼마나 맞았는지 사후 검증이 되어야
-- 조건 분기 알고리즘을 고칠 수 있다. 한 컬럼으로 합치면 정정 이력이 사라진다.

create table daily_checkins (
  id                 bigserial primary key,
  user_id            uuid not null references users(id) on delete cascade,
  log_date           date not null,
  available_minutes  int  check (available_minutes between 0 and 1440),
  condition_inferred text check (condition_inferred in ('good', 'normal', 'tired')),
  condition_final    text check (condition_final    in ('good', 'normal', 'tired')),
  condition_accepted boolean,                       -- 추론을 사용자가 그대로 수용했는지
  focus_area         text check (focus_area in
                       ('sleep', 'hydration', 'move', 'focus', 'meal')),
  is_rest_day        boolean not null default false, -- 쉬는 날/여행. 집계에서 완전 제외된다
  created_at         timestamptz not null default now(),
  unique (user_id, log_date)
);

create index idx_daily_checkins_recent on daily_checkins (user_id, log_date desc);


-- =============================================================
-- 6. 실행 기록
-- =============================================================
-- routine_runs는 스케줄러가 아니라 GET /today 첫 호출 때 지연 생성한다.
-- (배치 미가동으로 오늘이 빈 화면이 되는 실패 모드를 없애기 위함)
-- 부수 효과: 앱을 안 연 날은 run 자체가 없으므로 완료율 계산에서 자연히 빠진다.
--           8/8의 "공백을 0점으로 처리하지 않는다" 결정과 그대로 맞는다.

create table routine_runs (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references users(id) on delete cascade,
  routine_id   uuid not null references routines(id) on delete cascade,
  run_date     date not null,                       -- users.timezone 기준 날짜
  scheduled_at timestamptz not null,
  started_at   timestamptz,
  finished_at  timestamptz,
  status       text not null default 'scheduled'
                 check (status in ('scheduled', 'in_progress', 'done', 'skipped')),
  created_at   timestamptz not null default now(),
  -- ponytail: 하루 1회 발생으로 고정. 같은 장소 재진입은 별도 run을 만들지 않는다.
  unique (routine_id, run_date)
);

create index idx_routine_runs_today on routine_runs (user_id, run_date);

-- status 'unknown' = 결측 다음날 "어제 하셨어요?"에 [기억 안 남]을 고른 경우.
-- 실패가 아니라 판정 불가다. v_daily_states에서 분모에서 빠진다.
create table task_logs (
  id               bigserial primary key,
  user_id          uuid not null references users(id) on delete cascade,
  routine_run_id   uuid not null references routine_runs(id) on delete cascade,
  routine_task_id  uuid not null references routine_tasks(id),
  action_id        uuid not null references actions(id),  -- 그날의 난이도 스냅샷. 트리거가 채운다.
  status           text not null check (status in ('done', 'skipped', 'partial', 'unknown')),
  completed_at     timestamptz,
  duration_seconds int check (duration_seconds >= 0),
  memo             text,
  created_at       timestamptz not null default now(),
  unique (routine_run_id, routine_task_id)
);

create index idx_task_logs_history on task_logs (routine_task_id, created_at desc);


-- =============================================================
-- 7. 난이도 조정
-- =============================================================

create table adjustments (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references users(id) on delete cascade,
  routine_task_id  uuid not null references routine_tasks(id),
  before_action_id uuid not null references actions(id),
  after_action_id  uuid not null references actions(id),
  trigger_type     text not null check (trigger_type in
                     ('red_signal', 'streak_up', 'user_manual')),
  reason           text not null,                   -- 사용자에게 그대로 노출되는 문장
  created_at       timestamptz not null default now(),
  check (before_action_id <> after_action_id)
);

create index idx_adjustments_recent on adjustments (user_id, created_at desc);


-- =============================================================
-- 8. 외부 연동
-- =============================================================

create table fcm_tokens (
  id           bigserial primary key,
  user_id      uuid not null references users(id) on delete cascade,
  token        text not null unique,
  platform     text not null check (platform in ('android', 'ios')),
  last_seen_at timestamptz not null default now(),
  created_at   timestamptz not null default now()
);

create index idx_fcm_tokens_user on fcm_tokens (user_id);

create table calendar_connections (
  id                uuid primary key default gen_random_uuid(),
  user_id           uuid not null references users(id) on delete cascade,
  provider          text not null default 'google' check (provider in ('google')),
  refresh_token_enc bytea not null,                 -- 앱에서 암호화한 값만 저장
  scope             text not null,
  connected_at      timestamptz not null default now(),
  revoked_at        timestamptz,
  unique (user_id, provider)
);

-- ★ 신규. AAC 실측값 — 자가보고를 대체한다.
-- data를 jsonb로 둔 이유: AAC 검진 항목이 확정되지 않았고, 확정돼도 바뀔 수 있다.
create table health_checkups (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references users(id) on delete cascade,
  source      text not null default 'aac' check (source in ('aac')),
  measured_on date not null,
  data        jsonb not null,
  created_at  timestamptz not null default now(),
  unique (user_id, source, measured_on)
);

create index idx_health_checkups_recent on health_checkups (user_id, measured_on desc);

-- ★ 신규(v0.8). 사용자가 직접 입력한 일정 — PRD FR-010.
-- 캘린더 밀도 계산의 입력이 Google Calendar 하나뿐이면 구글을 안 쓰는 사용자는
-- 밀도가 항상 0이 된다. 이 표가 그 공백을 메운다.
-- place_text는 좌표가 아니라 문자열이다. 위치 판정은 places/지오펜스가 담당한다.
create table user_events (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references users(id) on delete cascade,
  title      text,                                   -- PRD Q-004 미결: 저장 여부·보존 기간
  starts_at  timestamptz not null,
  ends_at    timestamptz not null,
  place_text text,                                   -- 장소 문자열 (좌표 아님)
  created_at timestamptz not null default now(),
  check (ends_at >= starts_at)
);

create index idx_user_events_range on user_events (user_id, starts_at);


-- =============================================================
-- 9. 트리거 — 세 원칙을 DB에서 강제
-- =============================================================

-- 9.1 동의 기록은 append-only
create or replace function deny_mutation() returns trigger as $$
begin
  raise exception '% is append-only', tg_table_name;
end;
$$ language plpgsql;

create trigger trg_user_consents_append_only
  before update or delete on user_consents
  for each row execute function deny_mutation();


-- 9.2 과거 기록 동결 — 로그의 난이도는 삽입 시점 값으로 고정
create or replace function snapshot_task_action() returns trigger as $$
begin
  select rt.action_id into new.action_id
    from routine_tasks rt
   where rt.id = new.routine_task_id;

  if new.action_id is null then
    raise exception 'routine_task % not found', new.routine_task_id;
  end if;
  return new;
end;
$$ language plpgsql;

create trigger trg_task_logs_snapshot
  before insert on task_logs
  for each row execute function snapshot_task_action();

create or replace function freeze_task_action() returns trigger as $$
begin
  if new.action_id is distinct from old.action_id then
    raise exception 'task_logs.action_id is frozen';
  end if;
  return new;
end;
$$ language plpgsql;

create trigger trg_task_logs_freeze
  before update on task_logs
  for each row execute function freeze_task_action();


-- 9.3 소급 입력 상한 — "어제 하셨어요?"가 과거를 무제한 고치지 못하게 막는다
-- 무제한이면 완료율이 의미를 잃는다. 7일은 v0.5부터 쓰던 값.
create or replace function limit_backfill() returns trigger as $$
declare
  v_run_date date;
begin
  select rr.run_date into v_run_date
    from routine_runs rr where rr.id = new.routine_run_id;

  if (current_date - v_run_date) > 7 then
    raise exception 'backfill limit: run_date % is older than 7 days', v_run_date;
  end if;
  return new;
end;
$$ language plpgsql;

create trigger trg_task_logs_backfill_limit
  before insert on task_logs
  for each row execute function limit_backfill();


-- 9.4 난이도 조정의 유일한 입구 — adjustments INSERT가 routine_tasks에 적용된다
create or replace function apply_adjustment() returns trigger as $$
declare
  v_owner      uuid;
  v_before_key text;
  v_after_key  text;
begin
  select r.user_id into v_owner
    from routine_tasks rt
    join routines r on r.id = rt.routine_id
   where rt.id = new.routine_task_id;

  if v_owner is null then
    raise exception 'routine_task % not found', new.routine_task_id;
  end if;

  if v_owner <> new.user_id then
    raise exception 'user_id mismatch: routine_task belongs to another user';
  end if;

  select ladder_key into v_before_key from actions where id = new.before_action_id;
  select ladder_key into v_after_key  from actions where id = new.after_action_id;

  if v_before_key is distinct from v_after_key then
    raise exception 'ladder mismatch: % -> %', v_before_key, v_after_key;
  end if;

  -- 낙관적 잠금: 이미 다른 조정이 적용됐으면 실패시킨다
  update routine_tasks
     set action_id = new.after_action_id
   where id = new.routine_task_id
     and action_id = new.before_action_id;

  if not found then
    raise exception 'stale adjustment: routine_task % is not at action %',
      new.routine_task_id, new.before_action_id;
  end if;

  return new;
end;
$$ language plpgsql;

create trigger trg_apply_adjustment
  after insert on adjustments
  for each row execute function apply_adjustment();


-- =============================================================
-- 10. 판정 뷰 — 저장하지 않고 매번 계산
-- =============================================================
-- 8/8 결정 세 가지가 여기 반영되어 있다.
--   (1) is_rest_day 인 날은 행 자체를 만들지 않는다 = "집계에서 완전 제외"
--   (2) status='unknown' 은 분모에서 뺀다 = "기억 안 남"을 실패로 세지 않는다
--   (3) 신호는 green / yellow / red 3색뿐이다. 4번째 상태(gray)는 두지 않는다.
--       판정할 게 없는 날은 색을 주는 대신 행을 만들지 않는다 — (1)과 같은 규칙이다.
--
-- ponytail: 분모는 현재 살아있는 routine_tasks 수. 과거에 태스크를 추가·삭제하면
--           그 날의 분모가 소급 변동한다. 정확히 하려면 run 생성 시점의 태스크 수를
--           routine_runs에 박아야 하는데, 지금 스코프에선 과설계다.

create view v_daily_states as
with per_run as (
  select rr.user_id,
         rr.run_date,
         (select count(*) from routine_tasks rt
           where rt.routine_id = rr.routine_id
             and rt.archived_at is null)              as expected,
         (select count(*) from task_logs tl
           where tl.routine_run_id = rr.id
             and tl.status = 'done')                  as done,
         (select count(*) from task_logs tl
           where tl.routine_run_id = rr.id
             and tl.status = 'unknown')               as unknown_cnt
    from routine_runs rr
    left join daily_checkins dc
      on dc.user_id = rr.user_id
     and dc.log_date = rr.run_date
   where coalesce(dc.is_rest_day, false) = false
),
agg as (
  select user_id,
         run_date,
         sum(done)::int                                       as done_count,
         greatest(sum(expected) - sum(unknown_cnt), 0)::int    as expected_count
    from per_run
   group by user_id, run_date
)
select user_id,
       run_date,
       done_count,
       expected_count,
       round(done_count::numeric / expected_count, 2)            as completion_rate,
       -- 컷오프 0.7 / 0.3은 여전히 미결. 기획 확정 후 이 뷰만 고치면 된다.
       case
         when done_count::numeric / expected_count >= 0.7       then 'green'
         when done_count::numeric / expected_count >= 0.3       then 'yellow'
         else 'red'
       end                                                      as signal
  from agg
 where expected_count > 0;   -- 판정할 게 없는 날은 행 없음. 신호는 3색뿐이다.


-- =============================================================
-- 11. FastAPI 롤 — 읽기 전용 + adjustments INSERT만
-- =============================================================

create role hq_ai login password 'change-me';   -- 배포 전 반드시 교체

grant usage on schema public to hq_ai;
grant select on all tables in schema public to hq_ai;
grant insert on adjustments to hq_ai;

alter default privileges in schema public
  grant select on tables to hq_ai;


-- =============================================================
-- 부록. 심사 시연 (psql 한 줄)
-- =============================================================
-- insert into adjustments (user_id, routine_task_id, before_action_id, after_action_id,
--                          trigger_type, reason)
-- values ('<user>', '<task>', '<10분 산책>', '<3분 산책>',
--         'red_signal', '최근 5일 연속 미완료라 한 단계 낮췄어요');
--
-- 이 한 줄이 routine_tasks.action_id를 바꾼다. 지난 기록의 난이도는 그대로 남는다.
