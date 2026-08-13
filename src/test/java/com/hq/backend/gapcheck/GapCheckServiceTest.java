package com.hq.backend.gapcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.gapcheck.dto.GapCheckRequest;
import com.hq.backend.gapcheck.dto.GapCheckResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class GapCheckServiceTest {

    @Mock private GapCheckRepository gapCheckRepository;

    private GapCheckService service() {
        return new GapCheckService(gapCheckRepository);
    }

    @Test
    void 같은_날짜에_이미_응답했으면_409를_던진다() {
        UUID userId = UUID.randomUUID();
        LocalDate logDate = LocalDate.now();
        when(gapCheckRepository.existsByUserIdAndLogDate(userId, logDate)).thenReturn(true);
        var request = new GapCheckRequest(logDate, GapResponse.NOT_COMPLETED);

        assertThatThrownBy(() -> service().record(userId, request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE");
    }

    @Test
    void 미래_날짜면_400을_던진다() {
        UUID userId = UUID.randomUUID();
        var request = new GapCheckRequest(LocalDate.now().plusDays(1), GapResponse.UNSURE);

        assertThatThrownBy(() -> service().record(userId, request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_DATE");
    }

    @Test
    void 정상_입력이면_저장하고_응답을_그대로_반환한다() {
        UUID userId = UUID.randomUUID();
        LocalDate logDate = LocalDate.now();
        when(gapCheckRepository.existsByUserIdAndLogDate(userId, logDate)).thenReturn(false);
        ArgumentCaptor<GapCheck> captor = ArgumentCaptor.forClass(GapCheck.class);
        when(gapCheckRepository.save(captor.capture())).thenAnswer(inv -> captor.getValue());
        var request = new GapCheckRequest(logDate, GapResponse.UNSURE);

        GapCheckResponse response = service().record(userId, request);

        assertThat(response.logDate()).isEqualTo(logDate);
        assertThat(response.response()).isEqualTo(GapResponse.UNSURE);
    }

    // existsByUserIdAndLogDate 확인과 save() 사이의 레이스 컨디션 — unique(user_id, log_date)를
    // 뚫고 들어온 동시 요청은 DataIntegrityViolationException으로 온다.
    @Test
    void 사전_확인과_save_사이에_레이스가_나도_깔끔한_409를_던진다() {
        UUID userId = UUID.randomUUID();
        LocalDate logDate = LocalDate.now();
        when(gapCheckRepository.existsByUserIdAndLogDate(userId, logDate)).thenReturn(false);
        when(gapCheckRepository.save(any(GapCheck.class))).thenThrow(new DataIntegrityViolationException("unique violation"));
        var request = new GapCheckRequest(logDate, GapResponse.COMPLETED);

        assertThatThrownBy(() -> service().record(userId, request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE");
    }
}
