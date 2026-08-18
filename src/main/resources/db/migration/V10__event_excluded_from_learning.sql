-- API 명세 §15.3 POST /me/personalization/revert — 값 복원에 그치지 않고 해당 표본을
-- 학습에서 영구 제외한다. 어떤 이벤트의 결과를 다음 보정 계산에서 빼야 하는지 표시하는 플래그.
alter table event add column excluded_from_learning boolean not null default false;
