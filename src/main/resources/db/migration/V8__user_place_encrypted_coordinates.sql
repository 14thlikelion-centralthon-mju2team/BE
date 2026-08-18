-- API 명세 §5 · TRD §14.3 — 장소 좌표는 애플리케이션 레벨 AES-GCM 암호화가 요구된다.
-- UserPlace 엔티티를 처음 만들 때(SET-01) "암호화 컨버터는 Service 계층 붙일 때 추가한다"고
-- 미뤄뒀던 것을 /places API 구현과 함께 반영한다. 암호화된 값은 숫자 범위를 DB가 검증할 수
-- 없으므로 ck_user_place_lat/lng는 제거하고 애플리케이션(PlaceService)에서 검증한다.
-- 아직 운영 데이터가 없어(count=0) 컬럼을 그대로 드롭·재생성한다.

alter table user_place drop constraint ck_user_place_lat;
alter table user_place drop constraint ck_user_place_lng;

alter table user_place drop column lat;
alter table user_place drop column lng;

alter table user_place add column lat_enc bytea not null;
alter table user_place add column lng_enc bytea not null;
