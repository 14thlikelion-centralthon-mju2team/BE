-- WIS/RLS 원천 데이터가 모두 없을 때 0/low를 저장하면 데이터 부재를 실제 낮은 부담으로
-- 오인할 수 있다. 이 경우 dwl_score는 null, dwl_band는 unknown으로 보존한다.
alter table daily_wellness_summary
    alter column dwl_score drop not null;

alter table daily_wellness_summary
    drop constraint ck_summary_dwl_band;

alter table daily_wellness_summary
    add constraint ck_summary_dwl_band
        check (
            (dwl_score is null and dwl_band = 'unknown')
            or (dwl_score between 0 and 39 and dwl_band = 'low')
            or (dwl_score between 40 and 69 and dwl_band = 'mid')
            or (dwl_score between 70 and 100 and dwl_band = 'high')
        );
