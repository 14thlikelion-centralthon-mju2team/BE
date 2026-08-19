-- M2 attribution data capture: actual prep completion, cold-start one-off state,
-- and event mutation timing relative to the plan revision.
alter table event
    add column updated_at timestamptz;
update event set updated_at = created_at where updated_at is null;
alter table event
    alter column updated_at set not null;

alter table event_execution
    add column actual_prep_finished_at timestamptz;

alter table user_prep_estimate
    add column cold_start_adjusted boolean not null default false;

alter table event_action_log drop constraint ck_action_type;
alter table event_action_log add constraint ck_action_type
    check (action_type in (
        'prep_started', 'prep_finished', 'snoozed', 'departed', 'item_checked', 'excluded', 'arrived'
    ));
