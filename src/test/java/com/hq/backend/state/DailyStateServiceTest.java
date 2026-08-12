package com.hq.backend.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.state.dto.DailyStateResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DailyStateServiceTest {

    @Mock private DailyStateRepository dailyStateRepository;

    private DailyStateService service() {
        return new DailyStateService(dailyStateRepository);
    }

    @Test
    void from이_to보다_늦으면_400을_던진다() {
        UUID userId = UUID.randomUUID();
        LocalDate to = LocalDate.now();
        LocalDate from = to.plusDays(1);

        assertThatThrownBy(() -> service().getStates(userId, from, to))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_RANGE");
    }

    @Test
    void 뷰에_행이_없는_날은_green이_아니라_data_insufficient를_반환한다() {
        UUID userId = UUID.randomUUID();
        LocalDate day = LocalDate.now();
        when(dailyStateRepository.findByUserIdAndRunDateBetweenOrderByRunDateAsc(userId, day, day))
                .thenReturn(List.of());

        DailyStateResponse response = service().getStates(userId, day, day).days().get(0);

        assertThat(response.state()).isEqualTo(State.DATA_INSUFFICIENT);
        assertThat(response.performed()).isFalse();
    }

    @Test
    void 뷰에_행이_있으면_signal을_그대로_상태로_변환한다() {
        UUID userId = UUID.randomUUID();
        LocalDate day = LocalDate.now();
        DailyState row = DailyState.builder()
                .userId(userId)
                .runDate(day)
                .doneCount(4)
                .expectedCount(5)
                .completionRate(new BigDecimal("0.80"))
                .signal("green")
                .build();
        when(dailyStateRepository.findByUserIdAndRunDateBetweenOrderByRunDateAsc(userId, day, day))
                .thenReturn(List.of(row));

        DailyStateResponse response = service().getStates(userId, day, day).days().get(0);

        assertThat(response.state()).isEqualTo(State.GREEN);
        assertThat(response.performed()).isTrue();
        assertThat(response.reasons()).containsExactly("완료율 80%로 기준(70%) 이상");
    }
}
