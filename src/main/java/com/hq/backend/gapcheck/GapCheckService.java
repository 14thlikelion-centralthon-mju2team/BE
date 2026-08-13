package com.hq.backend.gapcheck;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.gapcheck.dto.GapCheckRequest;
import com.hq.backend.gapcheck.dto.GapCheckResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GapCheckService {

    private final GapCheckRepository gapCheckRepository;

    @Transactional
    public GapCheckResponse record(UUID userId, GapCheckRequest request) {
        if (request.logDate().isAfter(LocalDate.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE", "미래 날짜는 입력할 수 없습니다.");
        }
        if (gapCheckRepository.existsByUserIdAndLogDate(userId, request.logDate())) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE", "같은 날짜에 이미 응답했습니다.");
        }

        GapCheck saved;
        try {
            saved = gapCheckRepository.save(GapCheck.builder()
                    .userId(userId)
                    .logDate(request.logDate())
                    .response(request.response().name().toLowerCase())
                    .createdAt(Instant.now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // existsByUserIdAndLogDate 확인과 save() 사이에 동시 요청이 끼어들어 unique(user_id,
            // log_date)를 뚫은 경우 — CheckinService와 동일한 패턴으로 처리.
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE", "같은 날짜에 이미 응답했습니다.");
        }

        return toResponse(saved);
    }

    private GapCheckResponse toResponse(GapCheck gapCheck) {
        return new GapCheckResponse(
                gapCheck.getLogDate(),
                GapResponse.valueOf(gapCheck.getResponse().toUpperCase()),
                gapCheck.getCreatedAt());
    }
}
