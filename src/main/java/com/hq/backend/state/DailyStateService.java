package com.hq.backend.state;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.state.dto.DailyStateResponse;
import com.hq.backend.state.dto.StatesResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DailyStateService {

    private final DailyStateRepository dailyStateRepository;

    @Transactional(readOnly = true)
    public StatesResponse getStates(UUID userId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "from은 to보다 늦을 수 없습니다.");
        }

        Map<LocalDate, DailyState> byDate = dailyStateRepository
                .findByUserIdAndRunDateBetweenOrderByRunDateAsc(userId, from, to).stream()
                .collect(Collectors.toMap(DailyState::getRunDate, Function.identity()));

        List<DailyStateResponse> days = from.datesUntil(to.plusDays(1))
                .map(date -> toResponse(date, byDate.get(date)))
                .toList();

        return new StatesResponse(days);
    }

    // 뷰에 행이 없으면(휴식일 또는 그날 앱을 안 연 경우 등) Green으로 추정하지 않고 반드시 DATA_INSUFFICIENT를 반환한다(D-014).
    private DailyStateResponse toResponse(LocalDate date, DailyState state) {
        if (state == null) {
            return new DailyStateResponse(date, State.DATA_INSUFFICIENT, null, List.of("판단할 기록이 부족함"), false);
        }
        State signal = State.valueOf(state.getSignal().toUpperCase());
        return new DailyStateResponse(date, signal, "high", List.of(reasonFor(signal, state)), true);
    }

    private String reasonFor(State signal, DailyState state) {
        int rate = (int) Math.round(state.getCompletionRate().doubleValue() * 100);
        return switch (signal) {
            case GREEN -> "완료율 " + rate + "%로 기준(70%) 이상";
            case YELLOW -> "완료율 " + rate + "%로 기준(30~70%) 사이";
            case RED -> "완료율 " + rate + "%로 기준(30%) 미만";
            case DATA_INSUFFICIENT -> "판단할 기록이 부족함";
        };
    }
}
