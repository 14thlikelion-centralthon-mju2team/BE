-- M3 evaluate의 일정 중 push 후보를 준비 카드 action과 분리해 보존한다.
alter table plan_wellness_score
    add column armed_action_code text;
