-- M3 optional environment provenance for runtime evaluate. Unknown remains null/degraded.
alter table plan_context
    add column air_grade text,
    add column feels_like_min numeric(6, 2),
    add column feels_like_max numeric(6, 2);
