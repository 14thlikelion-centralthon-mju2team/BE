-- Approved M3 wellness v1 remote policy (TR-06). Values are JSONB so they can be
-- changed without application deployment; v1 seed remains immutable.
insert into engine_config (config_key, config_value, version, updated_by) values
    ('wellness_weight_version', '"m3-wellness-1.0.0"'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('wis_band_card', '40'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('wellness_event_min_raised', '85'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('mid_band_action_cap', '2'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('uv_high_index', '6.0'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('uv_full_load_index', '10.0'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('pm_loads', '{"moderate":0.25,"bad":0.70,"very_bad":1.00}'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('comfort_celsius', '{"min":5.0,"max":28.0}'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('heat_extreme_celsius', '33.0'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('cold_extreme_celsius', '-12.0'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('rain_thresholds', '{"light":30,"heavy":60,"thermal_bonus":0.30}'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('temp_swing_flag_celsius', '10.0'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('rls_weights', '{"prep_delay":0.45,"depart_delay":0.35,"critical_alert":0.20}'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('rls_delay_full_load_min', '30'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('rls_critical_alert_full_count', '2'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('dwl_bands', '{"mid":40,"high":70}'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('card_rushed_rls', '70'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('card_density_event_count', '4'::jsonb, 'm3-wellness-1.0.0', 'migration'),
    ('card_exposure_outdoor_min', '90'::jsonb, 'm3-wellness-1.0.0', 'migration')
on conflict (config_key) do nothing;
