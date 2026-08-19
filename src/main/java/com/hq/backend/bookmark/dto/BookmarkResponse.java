package com.hq.backend.bookmark.dto;

import com.hq.backend.bookmark.Bookmark;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BookmarkResponse(
        UUID bookmarkId,
        String placeName,
        BigDecimal lat,
        BigDecimal lng,
        String folder,
        Instant createdAt
) {

    public static BookmarkResponse from(Bookmark bookmark) {
        return new BookmarkResponse(
                bookmark.getBookmarkId(),
                bookmark.getPlaceName(),
                bookmark.getLat(),
                bookmark.getLng(),
                bookmark.getFolder(),
                bookmark.getCreatedAt());
    }
}
