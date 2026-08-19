-- Bookmark: 사용자 장소 즐겨찾기
CREATE TABLE bookmark (
    bookmark_id UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    place_name  VARCHAR(255) NOT NULL,
    lat         DECIMAL(9,6) NOT NULL,
    lng         DECIMAL(10,6) NOT NULL,
    folder      VARCHAR(20),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_bookmark_user_id ON bookmark(user_id);
