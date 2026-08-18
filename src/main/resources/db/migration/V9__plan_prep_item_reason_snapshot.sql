-- API 명세 §9.1 checklist[].reason — 웰니스/환경 근거로 추가된 항목의 근거 문장 스냅샷.
-- 엔진 응답(PlanEngineResponse.ChecklistItem.reason)을 그대로 저장한다.
alter table plan_prep_item add column reason_snapshot text;
