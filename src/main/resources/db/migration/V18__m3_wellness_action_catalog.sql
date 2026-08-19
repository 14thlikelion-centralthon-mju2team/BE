-- M3 wellness engine canonical action codes (TRD §7.3 / PRD §14.6).
-- Legacy M0 codes remain accepted because historical plan actions and schedules
-- still reference them; new M3 responses persist the canonical values below.
alter table plan_wellness_action
    drop constraint ck_wellness_action_code,
    add constraint ck_wellness_action_code check (
        action_code in (
            'uv_protect', 'pm_mask', 'temp_heat_prep', 'temp_cold_prep', 'rain_gear',
            'uv_reapply', 'pm_recheck', 'hydration_intake',
            'sunscreen', 'mask', 'hydration', 'outerwear', 'umbrella'
        )
    );

alter table wellness_event_schedule
    drop constraint ck_wellness_schedule_action,
    add constraint ck_wellness_schedule_action check (
        action_code in (
            'uv_protect', 'pm_mask', 'temp_heat_prep', 'temp_cold_prep', 'rain_gear',
            'uv_reapply', 'pm_recheck', 'hydration_intake',
            'sunscreen', 'mask', 'hydration', 'outerwear', 'umbrella'
        )
    );
