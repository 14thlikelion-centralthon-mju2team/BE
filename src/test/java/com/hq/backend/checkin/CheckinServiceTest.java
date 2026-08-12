package com.hq.backend.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hq.backend.checkin.dto.CheckinRequest;
import com.hq.backend.checkin.dto.CheckinResponse;
import com.hq.backend.common.exception.ApiException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CheckinServiceTest {

    @Mock private CheckinRepository checkinRepository;

    private CheckinService service() {
        return new CheckinService(checkinRepository);
    }

    @Test
    void 같은_날짜에_이미_입력했으면_409를_던진다() {
        UUID userId = UUID.randomUUID();
        LocalDate logDate = LocalDate.now();
        when(checkinRepository.existsByUserIdAndLogDate(userId, logDate)).thenReturn(true);
        var request = new CheckinRequest(logDate, 30, Condition.NORMAL, null);

        assertThatThrownBy(() -> service().record(userId, request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE");
    }

    @Test
    void 미래_날짜면_400을_던진다() {
        UUID userId = UUID.randomUUID();
        var request = new CheckinRequest(LocalDate.now().plusDays(1), 30, Condition.NORMAL, null);

        assertThatThrownBy(() -> service().record(userId, request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_DATE");
    }

    @Test
    void 정상_입력이면_저장하고_accepted_여부를_함께_반환한다() {
        UUID userId = UUID.randomUUID();
        LocalDate logDate = LocalDate.now();
        when(checkinRepository.existsByUserIdAndLogDate(userId, logDate)).thenReturn(false);
        ArgumentCaptor<Checkin> captor = ArgumentCaptor.forClass(Checkin.class);
        when(checkinRepository.save(captor.capture())).thenAnswer(inv -> captor.getValue());
        var request = new CheckinRequest(logDate, 30, Condition.TIRED, FocusArea.SLEEP);

        CheckinResponse response = service().record(userId, request);

        assertThat(response.conditionFinal()).isEqualTo(Condition.TIRED);
        assertThat(response.focusArea()).isEqualTo(FocusArea.SLEEP);
        // 지금 규칙은 NORMAL 고정 스텁이라, TIRED로 정정하면 accepted=false가 되어야 한다.
        assertThat(response.conditionAccepted()).isFalse();
    }
}
