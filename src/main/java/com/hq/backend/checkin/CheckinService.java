package com.hq.backend.checkin;

import com.hq.backend.checkin.dto.CheckinRequest;
import com.hq.backend.checkin.dto.CheckinResponse;
import com.hq.backend.common.exception.ApiException;
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
public class CheckinService {

    private final CheckinRepository checkinRepository;
    private final ConditionRuleEngine conditionRuleEngine;

    @Transactional
    public CheckinResponse record(UUID userId, CheckinRequest request) {
        if (request.logDate().isAfter(LocalDate.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE", "미래 날짜는 입력할 수 없습니다.");
        }
        if (checkinRepository.existsByUserIdAndLogDate(userId, request.logDate())) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE", "같은 날짜에 이미 입력했습니다.");
        }

        Condition inferred = conditionRuleEngine.infer(request.availableMinutes());

        Checkin saved;
        try {
            saved = checkinRepository.save(Checkin.builder()
                    .userId(userId)
                    .logDate(request.logDate())
                    .availableMinutes(request.availableMinutes())
                    .conditionInferred(inferred.name().toLowerCase())
                    .conditionFinal(request.conditionFinal().name().toLowerCase())
                    .conditionAccepted(inferred == request.conditionFinal())
                    .focusArea(request.focusArea() == null ? null : request.focusArea().name().toLowerCase())
                    .isRestDay(false)
                    .createdAt(Instant.now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // existsByUserIdAndLogDate 확인과 save() 사이에 동시 요청이 끼어들어 unique(user_id,
            // log_date)를 뚫은 경우 — AuthService.createGoogleUser와 동일한 패턴으로 처리.
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE", "같은 날짜에 이미 입력했습니다.");
        }

        return toResponse(saved);
    }

    private CheckinResponse toResponse(Checkin checkin) {
        return new CheckinResponse(
                checkin.getId(),
                checkin.getLogDate(),
                checkin.getAvailableMinutes(),
                Condition.valueOf(checkin.getConditionInferred().toUpperCase()),
                Condition.valueOf(checkin.getConditionFinal().toUpperCase()),
                checkin.getConditionAccepted(),
                checkin.getFocusArea() == null ? null : FocusArea.valueOf(checkin.getFocusArea().toUpperCase()),
                checkin.getCreatedAt());
    }
}
