package com.hq.backend.plan;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanServiceLockTest {

    private final PlanRevisionRepository planRevisionRepository = mock(PlanRevisionRepository.class);
    private final RouteOptionRepository routeOptionRepository = mock(RouteOptionRepository.class);
    private final EventRepository eventRepository = mock(EventRepository.class);
    private final PlanCreationService planCreationService = mock(PlanCreationService.class);
    private final PlanService service = new PlanService(
            planRevisionRepository,
            routeOptionRepository,
            mock(PlanPrepItemRepository.class),
            mock(PlanContextRepository.class),
            mock(com.hq.backend.wellness.PlanWellnessActionRepository.class),
            mock(com.hq.backend.wellness.PlanWellnessScoreRepository.class),
            eventRepository,
            planCreationService);

    @Test
    void recalculate는_event의_active_revision을_잠근_뒤_상태를_전이한다() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        PlanRevision active = activePlan(eventId);
        when(eventRepository.findByEventIdAndUserId(eventId, userId))
                .thenReturn(Optional.of(Event.builder().eventId(eventId).userId(userId).build()));
        when(planRevisionRepository.findActiveByEventIdForUpdate(eventId)).thenReturn(Optional.of(active));
        when(planCreationService.recompute(eq(userId), any(), eq(active.getOriginPlaceId()), eq(2),
                eq(active.getInputHash()), eq(null))).thenThrow(new IllegalStateException("engine unavailable"));

        assertThatThrownBy(() -> service.recalculate(userId, eventId))
                .isInstanceOf(IllegalStateException.class);

        verify(planRevisionRepository).findActiveByEventIdForUpdate(eventId);
        verify(planRevisionRepository, never()).findByEventIdAndPlanStatus(eventId, "active");
    }

    @Test
    void patch는_plan_revision을_잠근_뒤_active_상태를_확인한다() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        PlanRevision superseded = activePlan(eventId);
        superseded.setPlanStatus("superseded");
        when(planRevisionRepository.findByIdForUpdate(planId)).thenReturn(Optional.of(superseded));
        when(eventRepository.findByEventIdAndUserId(eventId, userId))
                .thenReturn(Optional.of(Event.builder().eventId(eventId).userId(userId).build()));

        assertThatThrownBy(() -> service.patch(userId, planId, null))
                .isInstanceOf(RuntimeException.class);

        verify(planRevisionRepository).findByIdForUpdate(planId);
        verify(planRevisionRepository, never()).findById(planId);
    }

    @Test
    void selectRoute는_plan_revision을_잠근_뒤_active_상태를_확인한다() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        PlanRevision superseded = activePlan(eventId);
        superseded.setPlanStatus("superseded");
        when(planRevisionRepository.findByIdForUpdate(planId)).thenReturn(Optional.of(superseded));
        when(eventRepository.findByEventIdAndUserId(eventId, userId))
                .thenReturn(Optional.of(Event.builder().eventId(eventId).userId(userId).build()));

        assertThatThrownBy(() -> service.selectRoute(userId, planId, UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);

        verify(planRevisionRepository).findByIdForUpdate(planId);
        verify(planRevisionRepository, never()).findById(planId);
    }

    private PlanRevision activePlan(UUID eventId) {
        return PlanRevision.builder()
                .eventId(eventId)
                .revisionNo(1)
                .originPlaceId(UUID.randomUUID())
                .inputHash("input-hash")
                .planStatus("active")
                .build();
    }
}
