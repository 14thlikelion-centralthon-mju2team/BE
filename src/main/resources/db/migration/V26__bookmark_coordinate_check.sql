-- V26: bookmark 좌표 범위 제약 추가
ALTER TABLE bookmark
    ADD CONSTRAINT ck_bookmark_lat CHECK (lat BETWEEN -90 AND 90),
    ADD CONSTRAINT ck_bookmark_lng CHECK (lng BETWEEN -180 AND 180);
