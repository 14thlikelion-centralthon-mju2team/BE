-- requested_at은 arrive_by/depart_at의 일정 기준 시각이라 미래일 수 있다.
-- TTL은 실제 검색이 발생한 created_at부터 계산해야 한다.
alter table route_search_option
    drop constraint ck_route_search_expiry;

alter table route_search_option
    add column created_at timestamptz not null default now();

alter table route_search_option
    add constraint ck_route_search_expiry check (expires_at > created_at);
