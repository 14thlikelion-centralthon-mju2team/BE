-- feat/ai-plan-engine(PR #88)이 반환하는 필드 중 plan_revision에 저장 컬럼이 없던 것을 채운다.
-- breakdown 6개 필드 중 arrival_buffer_minutes만 빠져 있었고, feasible/reasons/degraded는
-- 아예 없었다. reasons/degraded는 구조가 고정적이지 않아(reasons는 필드별 근거 문장 배열,
-- degraded는 사유 코드 배열) jsonb로 그대로 보관한다 — 별도 정규화 테이블은 지금 쓰임(조회
-- 시 조인 없이 계획 응답에 그대로 실어 보냄, TRD §5.2)에 비해 과하다.

alter table plan_revision
    add column arrival_buffer_minutes integer not null default 0,
    add column feasible boolean not null default true,
    add column reasons jsonb not null default '[]'::jsonb,
    add column degraded jsonb not null default '[]'::jsonb;

alter table plan_revision drop constraint ck_plan_minutes;
alter table plan_revision add constraint ck_plan_minutes
    check (
        estimated_prep_minutes >= 0
        and extra_prep_minutes >= 0
        and personal_routine_minutes >= 0
        and travel_minutes >= 0
        and traffic_buffer_minutes >= 0
        and arrival_buffer_minutes >= 0
    );
