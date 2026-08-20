package com.hq.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(OutputCaptureExtension.class)
class EventClassificationReviewRetentionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void ready와_주기_purge는_고정_clock의_24시간_cutoff와_500_batch를_사용한다() {
        EventClassificationReviewRetentionService service = mock(EventClassificationReviewRetentionService.class);
        when(service.purgeTitles(NOW.minusSeconds(86_400), 500)).thenReturn(new RetentionBatchResult(0, false));
        EventClassificationReviewRetentionScheduler scheduler = new EventClassificationReviewRetentionScheduler(
                service, Clock.fixed(NOW, ZoneOffset.UTC));

        scheduler.onApplicationReady(mock(ApplicationReadyEvent.class));
        scheduler.purgeTitles();

        verify(service, times(2)).purgeTitles(NOW.minusSeconds(86_400), 500);
    }

    @Test
    void daily_delete는_고정_clock의_90일_cutoff와_500_batch를_사용한다() {
        EventClassificationReviewRetentionService service = mock(EventClassificationReviewRetentionService.class);
        when(service.deleteExpired(NOW.minusSeconds(90L * 86_400), 500)).thenReturn(new RetentionBatchResult(0, false));
        EventClassificationReviewRetentionScheduler scheduler = new EventClassificationReviewRetentionScheduler(
                service, Clock.fixed(NOW, ZoneOffset.UTC));

        scheduler.deleteExpired();

        verify(service).deleteExpired(NOW.minusSeconds(90L * 86_400), 500);
    }

    @Test
    void 실패한_purge_pass는_다음_스케줄에서_재시도한다() {
        EventClassificationReviewRetentionService service = mock(EventClassificationReviewRetentionService.class);
        when(service.purgeTitles(NOW.minusSeconds(86_400), 500))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(new RetentionBatchResult(0, false));
        EventClassificationReviewRetentionScheduler scheduler = new EventClassificationReviewRetentionScheduler(
                service, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatCode(scheduler::purgeTitles).doesNotThrowAnyException();
        assertThatCode(scheduler::purgeTitles).doesNotThrowAnyException();

        verify(service, times(2)).purgeTitles(NOW.minusSeconds(86_400), 500);
    }

    @Test
    void scheduler_failure_log은_예외에_섞인_개인정보를_기록하지_않는다(CapturedOutput output) {
        String canary = "TASK10_RETENTION_LOG_CANARY_7d2ac1";
        EventClassificationReviewRetentionService service = mock(EventClassificationReviewRetentionService.class);
        when(service.deleteExpired(NOW.minusSeconds(90L * 86_400), 500))
                .thenThrow(new IllegalStateException(canary));
        EventClassificationReviewRetentionScheduler scheduler = new EventClassificationReviewRetentionScheduler(
                service, Clock.fixed(NOW, ZoneOffset.UTC));

        scheduler.deleteExpired();

        assertThat(output).doesNotContain(canary);
    }

    @Test
    void scheduling_contract은_5분_fixed_delay와_UTC_매일_삭제다() throws Exception {
        Method purge = EventClassificationReviewRetentionScheduler.class.getMethod("purgeTitles");
        Method delete = EventClassificationReviewRetentionScheduler.class.getMethod("deleteExpired");

        assertThat(purge.getAnnotation(Scheduled.class).fixedDelayString())
                .isEqualTo("${openai.classification.retention.purge-delay-ms:300000}");
        assertThat(delete.getAnnotation(Scheduled.class).cron())
                .isEqualTo("${openai.classification.retention.delete-cron:0 30 3 * * *}");
        assertThat(delete.getAnnotation(Scheduled.class).zone()).isEqualTo("UTC");
    }
}
