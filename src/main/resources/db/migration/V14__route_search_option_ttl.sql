-- CAL-05: 계획 생성 전 지도 검색 후보. 공개 routeOptionId는 30분 한정·사용자 범위 임시 키다.
-- 실제 계획 후보(route_option)와 분리해 plan_id 없는 검색 결과를 안전하게 보관한다.
create table route_search_option (
    route_search_option_id uuid primary key,
    search_session_id      uuid not null,
    user_id                uuid not null references users(user_id) on delete cascade,
    origin_place_id        uuid references user_place(place_id) on delete set null,
    origin_name            text not null,
    origin_lat             double precision not null,
    origin_lng             double precision not null,
    destination_lat        double precision not null,
    destination_lng        double precision not null,
    destination_name       text,
    anchor_mode            text not null,
    requested_at           timestamptz not null,
    route_rank             integer not null,
    route_type             text not null,
    total_seconds          integer not null,
    walk_seconds           integer not null,
    transfer_count         integer not null,
    outdoor_seconds        integer not null,
    depart_at              timestamptz,
    arrive_at              timestamptz,
    provider               text not null,
    raw_ref                text,
    expires_at             timestamptz not null,
    consumed_at            timestamptz,
    consumed_plan_id       uuid references plan_revision(plan_id) on delete set null,
    constraint ck_route_search_origin_lat check (origin_lat between -90 and 90),
    constraint ck_route_search_origin_lng check (origin_lng between -180 and 180),
    constraint ck_route_search_destination_lat check (destination_lat between -90 and 90),
    constraint ck_route_search_destination_lng check (destination_lng between -180 and 180),
    constraint ck_route_search_anchor check (anchor_mode in ('arrive_by', 'depart_at')),
    constraint ck_route_search_rank check (route_rank >= 1),
    constraint ck_route_search_type check (route_type in ('fastest', 'least_walk', 'least_transfer')),
    constraint ck_route_search_durations check (
        total_seconds >= 0 and walk_seconds >= 0 and walk_seconds <= total_seconds
        and transfer_count >= 0 and outdoor_seconds >= 0
    ),
    constraint ck_route_search_expiry check (expires_at > requested_at)
);

create index ix_route_search_option_owner_expiry
    on route_search_option (user_id, expires_at)
    where consumed_at is null;
create index ix_route_search_option_session
    on route_search_option (search_session_id, user_id, route_rank);
