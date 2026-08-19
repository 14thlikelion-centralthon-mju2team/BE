package com.hq.backend.bookmark;

import com.hq.backend.bookmark.dto.BookmarkCreateRequest;
import com.hq.backend.bookmark.dto.BookmarkResponse;
import com.hq.backend.common.exception.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BookmarkService {

    private final BookmarkRepository bookmarkRepository;

    @Transactional(readOnly = true)
    public List<BookmarkResponse> list(UUID userId, String folder) {
        List<Bookmark> bookmarks = (folder != null && !folder.isBlank())
                ? bookmarkRepository.findAllByUserIdAndFolder(userId, folder)
                : bookmarkRepository.findAllByUserId(userId);
        return bookmarks.stream().map(BookmarkResponse::from).toList();
    }

    @Transactional
    public BookmarkResponse create(UUID userId, BookmarkCreateRequest request) {
        Bookmark bookmark = Bookmark.builder()
                .userId(userId)
                .placeName(request.placeName())
                .lat(request.lat())
                .lng(request.lng())
                .folder(request.folder())
                .createdAt(Instant.now())
                .build();
        bookmark = bookmarkRepository.save(bookmark);
        return BookmarkResponse.from(bookmark);
    }

    @Transactional
    public void delete(UUID userId, UUID bookmarkId) {
        Bookmark bookmark = bookmarkRepository.findByBookmarkIdAndUserId(bookmarkId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BOOKMARK_NOT_FOUND",
                        "북마크를 찾을 수 없습니다."));
        bookmarkRepository.delete(bookmark);
    }
}
