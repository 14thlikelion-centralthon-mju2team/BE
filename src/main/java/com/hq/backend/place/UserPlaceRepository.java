package com.hq.backend.place;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPlaceRepository extends JpaRepository<UserPlace, UUID> {

    // /me/bootstrap이 사용 — 소프트 삭제(deletedAt)된 장소는 제외.
    List<UserPlace> findByUserIdAndDeletedAtIsNull(UUID userId);

    Optional<UserPlace> findByPlaceIdAndUserId(UUID placeId, UUID userId);

    // isPrimary를 새로 세팅하기 전에 기존 활성 대표 장소를 해제할 때 쓴다
    // (uq_active_primary_place 부분 유니크 인덱스 — 활성 대표 장소는 사용자당 1개).
    Optional<UserPlace> findByUserIdAndIsPrimaryTrueAndDeletedAtIsNull(UUID userId);
}
