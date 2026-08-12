package com.hq.backend.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class CheckinServiceTest {

    @Mock private CheckinRepository checkinRepository;

    private CheckinService service() {
        return new CheckinService(checkinRepository, new ConditionRuleEngine());
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
        // available_minutes=30은 규칙상 NORMAL 추론인데 사용자가 TIRED로 정정 → accepted=false
        assertThat(response.conditionInferred()).isEqualTo(Condition.NORMAL);
        assertThat(response.conditionAccepted()).isFalse();
    }

    // existsByUserIdAndLogDate 확인과 save() 사이의 레이스 컨디션 — unique(user_id, log_date)를
    // 뚫고 들어온 동시 요청은 DataIntegrityViolationException으로 온다(RestClientException이 아님).
    @Test
    void 사전_확인과_save_사이에_레이스가_나도_깔끔한_409를_던진다() {
        UUID userId = UUID.randomUUID();
        LocalDate logDate = LocalDate.now();
        when(checkinRepository.existsByUserIdAndLogDate(userId, logDate)).thenReturn(false);
        when(checkinRepository.save(any(Checkin.class))).thenThrow(new DataIntegrityViolationException("unique violation"));
        var request = new CheckinRequest(logDate, 30, Condition.NORMAL, null);

        assertThatThrownBy(() -> service().record(userId, request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE");
    }
}
