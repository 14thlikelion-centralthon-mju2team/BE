package com.hq.backend.place;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPlaceRepository extends JpaRepository<UserPlace, UUID> {

    // /me/bootstrap이 사용 — 소프트 삭제(deletedAt)된 장소는 제외.
    List<UserPlace> findByUserIdAndDeletedAtIsNull(UUID userId);
}
