-- TRD §9(TR-08)·상태 다이어그램 "enroute -> arrived : 지오펜스/원탭" — 도착 확정도
-- POST /plans/{planId}/actions로 들어온다(서버는 지오펜스를 실행하지 않고 판정 결과만
-- 수신한다, TRD 1020행). API 명세 §13의 action_type 목록에 arrived가 빠져 있던 걸 보정.
alter table event_action_log drop constraint ck_action_type;
alter table event_action_log add constraint ck_action_type
    check (action_type in ('prep_started', 'snoozed', 'departed', 'item_checked', 'excluded', 'arrived'));
