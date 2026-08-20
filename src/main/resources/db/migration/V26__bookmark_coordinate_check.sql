-- V26: bookmark 좌표 범위 제약 추가
-- NOT VALID는 기존 비정상 행을 보존해 배포 중단과 임의 데이터 손실을 피한다.
-- PostgreSQL은 NOT VALID CHECK도 신규 INSERT/UPDATE에는 즉시 적용한다.
-- 기존 행은 별도 데이터 교정 정책 수립 후 VALIDATE CONSTRAINT로 검증한다.
ALTER TABLE bookmark
    ADD CONSTRAINT ck_bookmark_lat CHECK (lat BETWEEN -90 AND 90) NOT VALID,
    ADD CONSTRAINT ck_bookmark_lng CHECK (lng BETWEEN -180 AND 180) NOT VALID;
