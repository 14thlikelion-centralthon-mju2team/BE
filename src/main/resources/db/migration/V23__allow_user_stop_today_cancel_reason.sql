-- WellnessEventSchedulerService persists this reason when a user stops the same action for the KST day.
alter table wellness_event_schedule
    drop constraint ck_wellness_cancel_reason,
    add constraint ck_wellness_cancel_reason check (
        cancel_reason is null
        or cancel_reason in ('indoor', 'plan_changed', 'user_completed', 'user_stop_today')
    );
