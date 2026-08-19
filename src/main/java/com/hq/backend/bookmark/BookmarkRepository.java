package com.hq.backend.bookmark;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookmarkRepository extends JpaRepository<Bookmark, UUID> {

    List<Bookmark> findAllByUserId(UUID userId);

    List<Bookmark> findAllByUserIdAndFolder(UUID userId, String folder);

    Optional<Bookmark> findByBookmarkIdAndUserId(UUID bookmarkId, UUID userId);
}
