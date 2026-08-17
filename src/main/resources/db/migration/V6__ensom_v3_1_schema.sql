-- Ensom ERD v3.1 신규 스키마
-- Source of truth: PRD v0.4.3, TRD v4.0, API 명세서 v5.0, ensom-milestones.md, MIGRATION.md
-- V5에서 users를 ALTER하고 재설계 대상 6개 테이블을 정리했다. 여기서는 ERD v3.1의
-- 나머지 신규 테이블 전체를 CREATE한다. pgcrypto는 V1__init.sql에서 이미 활성화됨.
--
-- 범위 밖(의도적으로 만들지 않음):
--   idempotency_record — MIGRATION.md §2 "유지" 판정에 따라 기존 idempotency_keys 재사용.
--   폐기 5패키지 테이블(daily_checkins/gap_checks/actions/routines/routine_tasks/
--     routine_runs/task_logs/adjustments) — TRD 부수 결정에 따라 M5까지 존치, 미변경.

-- -----------------------------------------------------------------------------
-- 1. 인증 · 동의 · 기기
-- -----------------------------------------------------------------------------

create table user_identity (
    identity_id          uuid primary key default gen_random_uuid(),
    user_id              uuid not null references users(user_id) on delete cascade,
    provider             text not null,
    provider_uid         text not null,
    linked_at            timestamptz not null default now(),
    revoked_at           timestamptz,
    constraint ck_identity_provider
        check (provider in ('email', 'google', 'apple')),
    constraint uq_identity_provider unique (provider, provider_uid)
);

create index ix_user_identity_user on user_identity (user_id);

create table user_credential (
    user_id              uuid primary key references users(user_id) on delete cascade,
    password_hash        text not null,
    password_algo        text not null default 'argon2id',
    password_updated_at  timestamptz not null default now(),
    failed_attempts      smallint not null default 0,
    locked_until         timestamptz,
    constraint ck_credential_algo check (password_algo = 'argon2id'),
    constraint ck_credential_failed_attempts check (failed_attempts >= 0)
);

create table auth_token (
    token_id             uuid primary key default gen_random_uuid(),
    user_id              uuid not null references users(user_id) on delete cascade,
    purpose              text not null,
    token_hash           text not null,
    expires_at           timestamptz not null,
    consumed_at          timestamptz,
    created_at           timestamptz not null default now(),
    constraint ck_auth_token_purpose
        check (purpose in ('email_verify', 'password_reset')),
    constraint ck_auth_token_expiry check (expires_at > created_at)
);

alter table auth_token add constraint uq_auth_token_hash unique (token_hash);

create index ix_auth_token_live
    on auth_token (user_id, purpose)
    where consumed_at is null;

-- refresh 토큰은 이메일/비밀번호 1회용 토큰과 별개. 기기별·사용자별 개별 폐기가 가능해야 한다.
create table refresh_token (
    refresh_token_id    uuid primary key default gen_random_uuid(),
    user_id             uuid not null references users(user_id) on delete cascade,
    push_device_id      uuid,
    token_hash          text not null,
    issued_at           timestamptz not null default now(),
    expires_at          timestamptz not null,
    revoked_at          timestamptz,
    constraint uq_refresh_token_hash unique (token_hash),
    constraint ck_refresh_token_expiry check (expires_at > issued_at)
);

create index ix_refresh_token_live
    on refresh_token (user_id, expires_at)
    where revoked_at is null;

create table user_setting (
    user_id                    uuid primary key references users(user_id) on delete cascade,
    initial_prep_minutes       integer,
    arrival_buffer_minutes     integer not null default 10,
    notification_sensitivity   text not null default 'normal',
    personalization_enabled    boolean not null default true,
    auto_manage_enabled        boolean not null default true,
    wellness_event_enabled     boolean not null default false,
    lockscreen_hide_sensitive  boolean not null default true,
    updated_at                 timestamptz not null default now(),
    constraint ck_user_setting_prep_minutes
        check (initial_prep_minutes is null or initial_prep_minutes >= 0),
    constraint ck_user_setting_arrival_buffer
        check (arrival_buffer_minutes >= 0)
);

create table user_permission (
    user_id              uuid not null references users(user_id) on delete cascade,
    permission_type      text not null,
    status               text not null,
    updated_at           timestamptz not null default now(),
    primary key (user_id, permission_type),
    constraint ck_permission_type
        check (permission_type in ('calendar', 'location', 'notification', 'background_location')),
    constraint ck_permission_status
        check (status in ('granted', 'denied', 'restricted', 'not_determined'))
);

create table user_consent (
    consent_event_id     uuid primary key default gen_random_uuid(),
    -- user_id는 의도적으로 nullable. 탈퇴해도 동의 이력은 법정 보존 기간 동안 남아야 하므로
    -- 사용자 삭제 시 이력 자체는 지우지 않고 신원만 분리한다(ON DELETE SET NULL).
    user_id              uuid references users(user_id) on delete set null,
    consent_type         text not null,
    policy_version       text not null,
    action               text not null,
    is_required          boolean not null default false,
    idempotency_key      uuid not null,
    recorded_at          timestamptz not null default now(),
    constraint ck_consent_type
        check (consent_type in ('terms', 'privacy', 'location', 'marketing')),
    constraint ck_consent_action
        check (action in ('agreed', 'revoked')),
    constraint uq_consent_idempotency unique (idempotency_key)
);

create index ix_user_consent_user_time
    on user_consent (user_id, recorded_at desc);

create table push_device (
    push_device_id       uuid primary key default gen_random_uuid(),
    user_id              uuid not null references users(user_id) on delete cascade,
    installation_id      uuid not null,
    current_token        text not null,
    token_status         text not null default 'active',
    platform             text not null,
    last_seen_at         timestamptz not null default now(),
    revoked_at           timestamptz,
    constraint uq_push_device_installation unique (installation_id),
    constraint ck_push_device_status
        check (token_status in ('active', 'inactive', 'invalid')),
    constraint ck_push_device_platform
        check (platform in ('ios', 'android', 'web'))
);

create index ix_push_device_user on push_device (user_id);

alter table refresh_token
    add constraint fk_refresh_token_push_device
    foreign key (push_device_id) references push_device(push_device_id) on delete set null;

-- -----------------------------------------------------------------------------
-- 2. 장소 · 웰니스 선호 · 맞춤 준비 규칙
-- -----------------------------------------------------------------------------

create table user_place (
    place_id             uuid primary key default gen_random_uuid(),
    user_id              uuid not null references users(user_id) on delete cascade,
    place_type           text not null,
    place_name           text not null,
    address              text not null,
    lat                  double precision not null,
    lng                  double precision not null,
    is_primary           boolean not null default false,
    deleted_at           timestamptz,
    constraint ck_user_place_lat check (lat between -90 and 90),
    constraint ck_user_place_lng check (lng between -180 and 180),
    constraint ck_user_place_type
        check (place_type in ('home', 'school', 'work', 'other'))
);

create unique index uq_active_primary_place
    on user_place (user_id)
    where is_primary = true and deleted_at is null;

create index ix_user_place_user_active
    on user_place (user_id)
    where deleted_at is null;

create table user_wellness_pref (
    user_id                    uuid not null references users(user_id) on delete cascade,
    wellness_topic             text not null,
    is_enabled                 boolean not null default false,
    remind_interval_minutes    integer,
    daily_event_cap            integer not null default 1,
    updated_at                 timestamptz not null default now(),
    primary key (user_id, wellness_topic),
    constraint ck_wellness_topic
        check (wellness_topic in ('uv', 'pm', 'temp', 'rain', 'hydration')),
    constraint ck_wellness_interval
        check (remind_interval_minutes is null or remind_interval_minutes > 0),
    constraint ck_wellness_daily_cap
        check (daily_event_cap >= 0)
);

create table user_prep_rule (
    prep_rule_id          uuid primary key default gen_random_uuid(),
    user_id               uuid not null references users(user_id) on delete cascade,
    rule_name             text not null,
    rule_category         text not null,
    action_type           text not null,
    rule_timing           text not null default 'pre_departure',
    default_minutes       integer,
    apply_event_kind      text,
    apply_time_band       text,
    apply_place_id        uuid references user_place(place_id) on delete set null,
    apply_weather         text,
    is_required            boolean not null default false,
    is_sensitive           boolean not null default false,
    from_chip              boolean not null default false,
    is_active              boolean not null default true,
    created_at             timestamptz not null default now(),
    deleted_at             timestamptz,
    constraint ck_prep_rule_category
        check (rule_category in ('supplement', 'medication', 'personal_item', 'routine', 'general_item')),
    constraint ck_prep_action_type
        check (action_type in ('carry', 'consume', 'purchase', 'timed_routine')),
    constraint ck_prep_rule_timing
        check (rule_timing in ('pre_departure', 'post_arrival')),
    constraint ck_prep_minutes check (
        (action_type = 'timed_routine' and default_minutes is not null and default_minutes > 0)
        or
        (action_type <> 'timed_routine' and default_minutes is null)
    ),
    constraint ck_medication_sensitive
        check (rule_category <> 'medication' or is_sensitive = true)
);

create index ix_prep_rule_user_active
    on user_prep_rule (user_id)
    where is_active = true and deleted_at is null;

-- -----------------------------------------------------------------------------
-- 3. 캘린더 · 일정
-- -----------------------------------------------------------------------------

create table calendar_connection (
    calendar_connection_id uuid primary key default gen_random_uuid(),
    user_id               uuid not null references users(user_id) on delete cascade,
    provider              text not null,
    external_account_id   text not null,
    refresh_token_enc     bytea,
    connected_at          timestamptz not null default now(),
    revoked_at            timestamptz,
    constraint ck_calendar_provider check (provider in ('google'))
);

create unique index uq_calendar_connection_account
    on calendar_connection (user_id, provider, external_account_id);

create table calendar_source (
    calendar_source_id     uuid primary key default gen_random_uuid(),
    calendar_connection_id uuid not null references calendar_connection(calendar_connection_id) on delete cascade,
    external_calendar_id   text not null,
    display_name           text not null,
    is_writable            boolean not null default false,
    is_default             boolean not null default false,
    sync_enabled           boolean not null default true,
    external_etag          text,
    deleted_at             timestamptz,
    constraint uq_calendar_source_external
        unique (calendar_connection_id, external_calendar_id)
);

create index ix_calendar_source_sync
    on calendar_source (calendar_connection_id)
    where deleted_at is null and sync_enabled = true;

create table event (
    event_id               uuid primary key default gen_random_uuid(),
    user_id                uuid not null references users(user_id) on delete cascade,
    calendar_source_id     uuid references calendar_source(calendar_source_id) on delete set null,
    external_event_id      text,
    source_type            text not null,
    starts_at              timestamptz not null,
    ends_at                timestamptz,
    is_all_day             boolean not null default false,
    location_state         text not null default 'undecided',
    destination_name       text,
    destination_lat        double precision,
    destination_lng        double precision,
    meeting_url            text,
    event_kind             text,
    display_label          text,
    auto_manage_excluded   boolean not null default false,
    status                 text not null default 'planned',
    created_at             timestamptz not null default now(),
    constraint ck_event_source_type
        check (source_type in ('internal', 'external', 'map_search')),
    constraint ck_event_location_state
        check (location_state in ('required_resolved', 'required_missing', 'not_required', 'undecided')),
    constraint ck_event_status
        check (status in ('planned', 'notified', 'preparing', 'enroute', 'arrived', 'closed', 'skipped', 'cancelled', 'unresolved')),
    constraint ck_event_time_order
        check (ends_at is null or ends_at >= starts_at),
    constraint ck_event_destination_lat
        check (destination_lat is null or destination_lat between -90 and 90),
    constraint ck_event_destination_lng
        check (destination_lng is null or destination_lng between -180 and 180),
    constraint ck_event_destination_pair
        check ((destination_lat is null and destination_lng is null)
            or (destination_lat is not null and destination_lng is not null))
);

create unique index uq_event_external
    on event (calendar_source_id, external_event_id)
    where calendar_source_id is not null and external_event_id is not null;

-- ERD의 event에는 deleted_at이 없다 — 삭제·취소는 status('cancelled'/'skipped')로만 표현한다.
create index ix_event_user_time
    on event (user_id, starts_at);

create index ix_event_active_status
    on event (status, starts_at);

create table event_classification_review (
    review_id                    uuid primary key default gen_random_uuid(),
    event_id                     uuid not null references event(event_id) on delete cascade,
    title_snapshot               text,
    question_type                text not null,
    suggested_value              text,
    user_answer                  text,
    model_version                text,
    classification_confidence    numeric(5, 4),
    asked_at                     timestamptz not null default now(),
    answered_at                  timestamptz,
    title_purged_at              timestamptz,
    constraint ck_classification_confidence
        check (classification_confidence is null or classification_confidence between 0 and 1),
    constraint ck_title_purged check (
        (title_snapshot is null and title_purged_at is not null)
        or
        (title_snapshot is not null and title_purged_at is null and answered_at is null)
    )
);

create index ix_classification_review_retention
    on event_classification_review (title_purged_at, asked_at);

create table event_prep_item (
    event_prep_item_id      uuid primary key default gen_random_uuid(),
    event_id                uuid not null references event(event_id) on delete cascade,
    source_prep_rule_id     uuid references user_prep_rule(prep_rule_id) on delete set null,
    item_name               text not null,
    action_type             text not null,
    estimated_minutes       integer not null default 0,
    is_required             boolean not null default false,
    is_sensitive            boolean not null default false,
    created_at              timestamptz not null default now(),
    constraint ck_event_prep_action_type
        check (action_type in ('carry', 'consume', 'purchase', 'timed_routine')),
    constraint ck_event_prep_minutes check (estimated_minutes >= 0)
);

create index ix_event_prep_item_event on event_prep_item (event_id);

-- -----------------------------------------------------------------------------
-- 4. 계획 · 경로 · 환경
-- -----------------------------------------------------------------------------

create table plan_revision (
    plan_id                    uuid primary key default gen_random_uuid(),
    event_id                   uuid not null references event(event_id) on delete cascade,
    revision_no                integer not null,
    origin_place_id            uuid references user_place(place_id) on delete set null,
    origin_snapshot_name       text,
    origin_snapshot_lat        double precision,
    origin_snapshot_lng        double precision,
    selected_route_option_id   uuid,
    prep_start_at              timestamptz not null,
    recommended_depart_at      timestamptz not null,
    target_arrive_at           timestamptz not null,
    estimated_prep_minutes     integer not null,
    extra_prep_minutes         integer not null default 0,
    personal_routine_minutes   integer not null default 0,
    travel_minutes             integer not null,
    traffic_buffer_minutes     integer not null default 0,
    prediction_confidence      text not null default 'low',
    plan_status                text not null default 'active',
    calc_version               text not null,
    next_eval_at               timestamptz,
    input_hash                 text,
    created_at                 timestamptz not null default now(),
    constraint uq_plan_revision_number unique (event_id, revision_no),
    constraint ck_plan_prediction_confidence
        check (prediction_confidence in ('high', 'mid', 'low')),
    constraint ck_plan_status
        check (plan_status in ('active', 'superseded')),
    constraint ck_plan_minutes
        check (
            estimated_prep_minutes >= 0
            and extra_prep_minutes >= 0
            and personal_routine_minutes >= 0
            and travel_minutes >= 0
            and traffic_buffer_minutes >= 0
        ),
    constraint ck_plan_origin_lat
        check (origin_snapshot_lat is null or origin_snapshot_lat between -90 and 90),
    constraint ck_plan_origin_lng
        check (origin_snapshot_lng is null or origin_snapshot_lng between -180 and 180),
    constraint ck_plan_origin_pair
        check ((origin_snapshot_lat is null and origin_snapshot_lng is null)
            or (origin_snapshot_lat is not null and origin_snapshot_lng is not null))
);

create unique index uq_active_plan_per_event
    on plan_revision (event_id)
    where plan_status = 'active';

create index plan_due
    on plan_revision (next_eval_at)
    where next_eval_at is not null and plan_status = 'active';

create index ix_plan_event_created
    on plan_revision (event_id, created_at desc);

create table route_option (
    route_option_id      uuid primary key default gen_random_uuid(),
    plan_id              uuid not null references plan_revision(plan_id) on delete cascade,
    route_rank           integer not null,
    route_type           text not null,
    total_minutes        integer not null,
    walk_minutes         integer not null,
    transfer_count       integer not null,
    depart_at            timestamptz,
    arrive_at            timestamptz,
    route_payload        jsonb,
    constraint uq_route_in_plan unique (plan_id, route_option_id),
    constraint uq_route_rank_in_plan unique (plan_id, route_rank),
    constraint ck_route_type
        check (route_type in ('fastest', 'least_walk', 'least_transfer')),
    constraint ck_route_rank check (route_rank >= 1),
    constraint ck_route_minutes
        check (total_minutes >= 0 and walk_minutes >= 0 and transfer_count >= 0),
    constraint ck_route_walk_not_total check (walk_minutes <= total_minutes)
);

alter table plan_revision
    add constraint fk_selected_route
    foreign key (plan_id, selected_route_option_id)
    references route_option (plan_id, route_option_id);

create table plan_context (
    plan_id                    uuid primary key references plan_revision(plan_id) on delete cascade,
    temperature                numeric(6, 2),
    feels_like                 numeric(6, 2),
    precipitation_prob         numeric(5, 2),
    uv_index                   smallint,
    pm10                       integer,
    pm25                       integer,
    traffic_delay_minutes      integer,
    estimated_outdoor_minutes  integer,
    weather_provider           text,
    air_provider               text,
    traffic_provider           text,
    observed_at                timestamptz,
    constraint ck_context_precipitation
        check (precipitation_prob is null or precipitation_prob between 0 and 100),
    constraint ck_context_uv check (uv_index is null or uv_index >= 0),
    constraint ck_context_pm check ((pm10 is null or pm10 >= 0) and (pm25 is null or pm25 >= 0)),
    constraint ck_context_minutes
        check ((traffic_delay_minutes is null or traffic_delay_minutes >= 0)
            and (estimated_outdoor_minutes is null or estimated_outdoor_minutes >= 0))
);

create table plan_wellness_score (
    plan_id                uuid primary key references plan_revision(plan_id) on delete cascade,
    uv_load                numeric(6, 5) not null,
    pm_load                numeric(6, 5) not null,
    thermal_load            numeric(6, 5) not null,
    outdoor_load            numeric(6, 5) not null,
    interest_multiplier     numeric(6, 5) not null,
    wis_score               smallint not null,
    wis_band                text not null,
    weight_version          text not null,
    calculated_at           timestamptz not null default now(),
    constraint ck_wis_loads check (
        uv_load between 0 and 1
        and pm_load between 0 and 1
        and thermal_load between 0 and 1
        and outdoor_load between 0 and 1
    ),
    constraint ck_wis_multiplier check (interest_multiplier between 1 and 1.25),
    constraint ck_wis_score check (wis_score between 0 and 100),
    constraint ck_wis_band check (
        (wis_score between 0 and 39  and wis_band = 'low')
        or (wis_score between 40 and 69 and wis_band = 'mid')
        or (wis_score between 70 and 100 and wis_band = 'high')
    )
);

create table plan_wellness_action (
    wellness_action_id     uuid primary key default gen_random_uuid(),
    plan_id                uuid not null references plan_revision(plan_id) on delete cascade,
    wellness_topic         text not null,
    action_code            text not null,
    action_label           text not null,
    display_rank            smallint not null,
    reason_snapshot        text not null,
    completion_status      text not null default 'proposed',
    responded_at           timestamptz,
    constraint uq_wellness_action_rank unique (plan_id, display_rank),
    constraint ck_wellness_rank check (display_rank between 1 and 3),
    constraint ck_wellness_action_topic
        check (wellness_topic in ('uv', 'pm', 'temp', 'rain', 'hydration')),
    constraint ck_wellness_action_code
        check (action_code in ('sunscreen', 'mask', 'hydration', 'outerwear', 'umbrella')),
    constraint ck_wellness_action_status
        check (completion_status in ('proposed', 'completed', 'dismissed'))
);

create index ix_plan_wellness_action_plan on plan_wellness_action (plan_id);

create table plan_prep_item (
    plan_prep_item_id       uuid primary key default gen_random_uuid(),
    plan_id                 uuid not null references plan_revision(plan_id) on delete cascade,
    prep_rule_id            uuid references user_prep_rule(prep_rule_id) on delete set null,
    event_prep_item_id      uuid references event_prep_item(event_prep_item_id) on delete set null,
    item_name_snapshot      text not null,
    action_type_snapshot    text not null,
    applied_minutes         integer not null default 0,
    is_sensitive            boolean not null default false,
    source_type             text not null,
    completion_status       text not null default 'pending',
    completed_at            timestamptz,
    constraint ck_plan_prep_action_type
        check (action_type_snapshot in ('carry', 'consume', 'purchase', 'timed_routine')),
    constraint ck_plan_prep_minutes check (applied_minutes >= 0),
    constraint ck_plan_prep_source_type
        check (source_type in ('rule', 'event_item', 'weather')),
    constraint ck_plan_prep_completion
        check (completion_status in ('pending', 'completed'))
);

create index ix_plan_prep_item_plan on plan_prep_item (plan_id);

-- -----------------------------------------------------------------------------
-- 5. 알림 · 행동 · 결과
-- -----------------------------------------------------------------------------

create table notification (
    notification_id       uuid primary key default gen_random_uuid(),
    plan_id               uuid not null references plan_revision(plan_id) on delete cascade,
    notification_category text not null,
    notification_type     text not null,
    scheduled_at          timestamptz not null,
    sent_at               timestamptz,
    delivery_status       text not null default 'scheduled',
    body_masked           text not null,
    trigger_reason        text not null,
    dedup_key             text not null,
    constraint uq_notification_dedup unique (dedup_key),
    constraint ck_noti_category check (
        (notification_type = 'wellness_event' and notification_category = 'wellness')
        or
        (notification_type <> 'wellness_event' and notification_category = 'time')
    ),
    constraint ck_notification_category
        check (notification_category in ('time', 'wellness')),
    constraint ck_notification_type
        check (notification_type in ('relaxed', 'critical', 'disruption', 'wellness_event')),
    constraint ck_notification_delivery
        check (delivery_status in ('scheduled', 'sent', 'delivered', 'failed', 'cancelled'))
);

create index ix_notification_pending
    on notification (scheduled_at)
    where sent_at is null and delivery_status in ('scheduled', 'failed');

create table wellness_event_schedule (
    wellness_event_id          uuid primary key default gen_random_uuid(),
    plan_id                    uuid not null references plan_revision(plan_id) on delete cascade,
    notification_id            uuid references notification(notification_id) on delete set null,
    action_code                text not null,
    interval_minutes_snapshot  integer,
    scheduled_at               timestamptz not null,
    sent_at                    timestamptz,
    response_action            text,
    user_rating                text,
    sequence_no                smallint not null default 1,
    cancelled_at               timestamptz,
    cancel_reason              text,
    constraint uq_wellness_event_once unique (plan_id, action_code, sequence_no),
    constraint uq_wellness_schedule_notification unique (notification_id),
    constraint ck_wellness_schedule_action
        check (action_code in ('sunscreen', 'mask', 'hydration', 'outerwear', 'umbrella')),
    constraint ck_wellness_schedule_interval
        check (interval_minutes_snapshot is null or interval_minutes_snapshot > 0),
    constraint ck_wellness_sequence check (sequence_no >= 1),
    constraint ck_wellness_response
        check (response_action is null or response_action in ('completed', 'snoozed', 'stop_today', 'ignored')),
    constraint ck_wellness_rating
        check (user_rating is null or user_rating in ('useful', 'not_relevant')),
    constraint ck_wellness_cancel_reason
        check (cancel_reason is null or cancel_reason in ('indoor', 'plan_changed', 'user_completed'))
);

create index ix_wellness_schedule_pending
    on wellness_event_schedule (scheduled_at)
    where cancelled_at is null and response_action is null;

create table event_action_log (
    action_log_id       uuid primary key default gen_random_uuid(),
    event_id            uuid not null references event(event_id) on delete cascade,
    plan_id             uuid references plan_revision(plan_id) on delete set null,
    notification_id     uuid references notification(notification_id) on delete set null,
    action_type         text not null,
    action_source       text not null,
    action_at           timestamptz not null,
    received_at         timestamptz not null default now(),
    confidence          numeric(5, 4),
    clock_skew          boolean not null default false,
    client_event_id     uuid not null,
    constraint uq_action_client_event unique (client_event_id),
    constraint ck_action_type
        check (action_type in ('prep_started', 'snoozed', 'departed', 'item_checked', 'excluded')),
    constraint ck_action_source
        check (action_source in ('user', 'geo', 'system')),
    constraint ck_action_confidence
        check (confidence is null or confidence between 0 and 1)
);

create index ix_action_log_event_time
    on event_action_log (event_id, action_at);

create table event_execution (
    event_id                 uuid primary key references event(event_id) on delete cascade,
    final_plan_id            uuid references plan_revision(plan_id) on delete set null,
    actual_prep_started_at   timestamptz,
    actual_departed_at       timestamptz,
    actual_arrived_at        timestamptz,
    arrival_result           text not null default 'unknown',
    result_source            text not null default 'inferred',
    actual_outdoor_minutes   integer,
    prep_delay_norm          numeric(6, 5),
    depart_delay_norm        numeric(6, 5),
    critical_alert_norm      numeric(6, 5),
    rush_load_score          smallint,
    created_at               timestamptz not null default now(),
    updated_at               timestamptz not null default now(),
    constraint ck_execution_arrival_result
        check (arrival_result in ('early', 'on_time', 'rushed', 'late', 'unknown')),
    constraint ck_execution_source
        check (result_source in ('user', 'geo', 'inferred')),
    constraint ck_execution_outdoor_minutes
        check (actual_outdoor_minutes is null or actual_outdoor_minutes >= 0),
    constraint ck_execution_norms check (
        (prep_delay_norm is null or prep_delay_norm between 0 and 1)
        and (depart_delay_norm is null or depart_delay_norm between 0 and 1)
        and (critical_alert_norm is null or critical_alert_norm between 0 and 1)
    ),
    constraint ck_execution_rush_score
        check (rush_load_score is null or rush_load_score between 0 and 100)
);

create table event_delay_reason (
    event_id          uuid not null references event(event_id) on delete cascade,
    reason_code       text not null,
    reason_source     text not null,
    confidence        numeric(5, 4),
    created_at        timestamptz not null default now(),
    primary key (event_id, reason_code),
    constraint ck_delay_reason_code
        check (reason_code in ('prep_late', 'prep_overrun', 'depart_late', 'traffic', 'external')),
    constraint ck_delay_reason_source
        check (reason_source in ('user', 'inferred')),
    constraint ck_delay_reason_confidence
        check (confidence between 0 and 1)
);

create table event_feedback (
    event_id                 uuid primary key references event(event_id) on delete cascade,
    prep_timing_assessment   text not null default 'unknown',
    arrival_result           text,
    rush_assessment          text,
    created_at               timestamptz not null default now(),
    constraint ck_feedback_prep_timing
        check (prep_timing_assessment in ('too_early', 'appropriate', 'too_late', 'unknown')),
    constraint ck_feedback_arrival
        check (arrival_result is null or arrival_result in ('early', 'on_time', 'rushed', 'late', 'unknown')),
    constraint ck_feedback_rush
        check (rush_assessment is null or rush_assessment in ('rushed', 'not_rushed', 'unknown'))
);

-- -----------------------------------------------------------------------------
-- 6. 추정 · 일일 요약 · 원격 설정
-- -----------------------------------------------------------------------------

create table daily_wellness_summary (
    summary_id                uuid primary key default gen_random_uuid(),
    user_id                   uuid not null references users(user_id) on delete cascade,
    summary_date              date not null,
    event_count               integer not null,
    total_outdoor_minutes     integer not null,
    outdoor_source            text not null default 'estimated',
    avg_wis_weighted           numeric(7, 3),
    avg_rls                    numeric(7, 3),
    dwl_score                  smallint not null,
    dwl_band                   text not null,
    card_scenario               text not null,
    card_message_snapshot       text not null,
    is_viewed                  boolean not null default false,
    created_at                 timestamptz not null default now(),
    constraint uq_daily_summary unique (user_id, summary_date),
    constraint ck_summary_counts
        check (event_count > 0 and total_outdoor_minutes >= 0),
    constraint ck_summary_outdoor_source
        check (outdoor_source in ('estimated', 'observed')),
    constraint ck_summary_dwl_score check (dwl_score between 0 and 100),
    constraint ck_summary_dwl_band
        check (
            (dwl_score between 0 and 39 and dwl_band = 'low')
            or (dwl_score between 40 and 69 and dwl_band = 'mid')
            or (dwl_score between 70 and 100 and dwl_band = 'high')
        ),
    constraint ck_summary_scenario
        check (card_scenario in ('default', 'exposure', 'density', 'rushed', 'stable'))
);

create index ix_daily_summary_user_date
    on daily_wellness_summary (user_id, summary_date desc);

create table user_prep_estimate (
    estimate_id          uuid primary key default gen_random_uuid(),
    user_id              uuid not null references users(user_id) on delete cascade,
    scope_type            text not null,
    scope_value           text,
    estimated_minutes     integer not null,
    sample_count          integer not null default 0,
    confidence            numeric(5, 4) not null default 0,
    model_version         text not null,
    adjustment_reason     text,
    valid_from            timestamptz not null default now(),
    valid_to              timestamptz,
    constraint ck_estimate_scope_type
        check (scope_type in ('global', 'event_kind', 'weather', 'origin_place', 'time_band')),
    constraint ck_estimate_minutes check (estimated_minutes >= 0),
    constraint ck_estimate_sample_count check (sample_count >= 0),
    constraint ck_estimate_confidence check (confidence between 0 and 1),
    constraint ck_estimate_validity check (valid_to is null or valid_to > valid_from)
);

create index ix_prep_estimate_lookup
    on user_prep_estimate (user_id, scope_type, valid_from desc);

create index ix_active_prep_estimate
    on user_prep_estimate (user_id, scope_type)
    where valid_to is null;

create table engine_config (
    config_key      text primary key,
    config_value    jsonb not null,
    version         text not null,
    updated_at      timestamptz not null default now(),
    updated_by      text
);

-- 제품 지표는 append-only. 좌표·제목·민감 준비 항목명은 애플리케이션이 payload에 넣지 않는다.
create table product_event (
    product_event_id    uuid primary key default gen_random_uuid(),
    user_id             uuid references users(user_id) on delete cascade,
    event_name          text not null,
    occurred_at         timestamptz not null,
    received_at         timestamptz not null default now(),
    client_event_id     uuid,
    payload              jsonb not null default '{}'::jsonb
);

create unique index uq_product_event_client
    on product_event (client_event_id)
    where client_event_id is not null;

create index ix_product_event_name_time
    on product_event (event_name, occurred_at desc);

create index ix_product_event_user_time
    on product_event (user_id, occurred_at desc);

-- -----------------------------------------------------------------------------
-- 7. 원격 설정 시드값 (TR-06) — 전부 부록 A 파라미터 레지스트리와 동일
-- -----------------------------------------------------------------------------

insert into engine_config (config_key, config_value, version, updated_by) values
    ('calc_version', '"3.1.0"'::jsonb, '3.1.0', 'migration'),
    ('weight_version', '"w1"'::jsonb, 'w1', 'migration'),
    ('arrival_buffer_min', '10'::jsonb, '3.1.0', 'migration'),
    ('traffic_buffer_min', '5'::jsonb, '3.1.0', 'migration'),
    ('rain_extra_prep_min', '5'::jsonb, '3.1.0', 'migration'),
    ('silent_shift_min', '2'::jsonb, '3.1.0', 'migration'),
    ('material_shift_min', '5'::jsonb, '3.1.0', 'migration'),
    ('seed_fallback_min', '30'::jsonb, '3.1.0', 'migration'),
    ('prep_ema_alpha', '0.30'::jsonb, 'w1', 'migration'),
    ('wis_weights', '{"uv":0.35,"pm":0.25,"temp":0.20,"outdoor":0.20}'::jsonb, 'w1', 'migration'),
    ('wis_interest_boost_max', '1.25'::jsonb, 'w1', 'migration'),
    ('outdoor_cap_min', '120'::jsonb, 'w1', 'migration'),
    ('wellness_event_min', '70'::jsonb, 'w1', 'migration'),
    ('wellness_event_per_schedule', '1'::jsonb, 'w1', 'migration'),
    ('daily_event_cap_default', '1'::jsonb, 'w1', 'migration'),
    ('dwl_weights', '{"wis":0.6,"rls":0.4}'::jsonb, 'w1', 'migration'),
    ('time_notification_budget', '3'::jsonb, '3.1.0', 'migration'),
    ('active_window_lead_min', '30'::jsonb, '3.1.0', 'migration'),
    ('geofence_origin_radius_m', '150'::jsonb, '3.1.0', 'migration'),
    ('geofence_dwell_sec', '90'::jsonb, '3.1.0', 'migration'),
    ('geofence_auto_confidence', '0.60'::jsonb, '3.1.0', 'migration')
;

-- -----------------------------------------------------------------------------
-- 8. 문서화 · 프라이버시 경계 주석
-- -----------------------------------------------------------------------------

comment on table plan_wellness_score is
    'WIS is an alert-priority value only; score_purpose=priority_only. It is not a medical, skin, stress, or health score.';
comment on column plan_wellness_score.wis_score is
    'score_purpose=priority_only; used to rank wellness actions and event notification eligibility.';
comment on table event_execution is
    'RLS is an operational rushed-arrival/planning-load indicator only; score_purpose=priority_only.';
comment on column event_execution.rush_load_score is
    'score_purpose=priority_only; never interpret as stress or mental-health measurement.';
comment on table daily_wellness_summary is
    'DWL is an alert-priority/recovery-card selection value only; score_purpose=priority_only.';
comment on column daily_wellness_summary.dwl_score is
    'score_purpose=priority_only; store for internal analysis and reproducibility, not as a medical score.';
comment on column event.display_label is
    'User-entered or user-approved display name only. External calendar title is never persisted here.';
comment on column event_classification_review.title_snapshot is
    'Temporary classification input. Application must purge it within 24 hours or immediately after answer.';
comment on column user_place.lat is
    'ERD/API compatibility column. TRD requires application-level AES-GCM; production must use a JPA converter or encrypted bytea design before storing sensitive coordinates.';
comment on column user_place.lng is
    'ERD/API compatibility column. TRD requires application-level AES-GCM; production must use a JPA converter or encrypted bytea design before storing sensitive coordinates.';
comment on column notification.body_masked is
    'Masked notification body. Sensitive preparation item names must not be persisted or rendered here.';
comment on table product_event is
    'Append-only product metrics. Do not put raw calendar titles, coordinates, or sensitive preparation item names in payload.';
