-- Bookmark lat/lng 범위 제약 추가 (lat: -90~90, lng: -180~180)
ALTER TABLE bookmark ADD CONSTRAINT chk_bookmark_lat CHECK (lat BETWEEN -90 AND 90);
ALTER TABLE bookmark ADD CONSTRAINT chk_bookmark_lng CHECK (lng BETWEEN -180 AND 180);
