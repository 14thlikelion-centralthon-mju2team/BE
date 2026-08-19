package com.hq.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventActionLog;
import com.hq.backend.event.EventActionLogRepository;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.wellness.WellnessEventSchedule;
import com.hq.backend.wellness.WellnessEventScheduleRepository;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationContractTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private EventRepository eventRepository;
    @Autowired private PlanRevisionRepository planRevisionRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private EventActionLogRepository eventActionLogRepository;
    @Autowired private WellnessEventScheduleRepository wellnessEventScheduleRepository;

    @Test
    void 오늘_알림은_FE계약의_slot_status_body_reaction을_반환하고_취소건은_제외한다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);
        Instant now = Instant.now();
        PlanRevision plan = createActivePlan(userId, now);

        Notification relaxed = saveNotification(plan.getPlanId(), "time", "relaxed", "sent", now, "준비 알림");
        saveNotification(plan.getPlanId(), "time", "critical", "scheduled", now, "출발 임박 알림");
        saveNotification(plan.getPlanId(), "time", "disruption", "failed", now, "변경 알림");
        Notification wellness = saveNotification(
                plan.getPlanId(), "wellness", "wellness_event", "sent", now, "웰니스 알림");
        saveNotification(plan.getPlanId(), "time", "disruption", "cancelled", now, "취소 알림");

        eventActionLogRepository.save(EventActionLog.builder()
                .eventId(plan.getEventId()).planId(plan.getPlanId()).notificationId(relaxed.getNotificationId())
                .actionType("prep_started").actionSource("user").actionAt(now).receivedAt(now)
                .clockSkew(false).clientEventId(UUID.randomUUID()).build());
        wellnessEventScheduleRepository.save(WellnessEventSchedule.builder()
                .planId(plan.getPlanId()).notificationId(wellness.getNotificationId()).actionCode("uv_reapply")
                .scheduledAt(now).sentAt(now).responseAction("completed").sequenceNo((short) 1).build());

        String response = mockMvc.perform(get("/notifications/today")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> rows = JsonPath.read(response, "$[*]");
        assertThat(rows).hasSize(4);
        assertNotification(rows, "relaxed", "A", "delivered", "준비 알림", "prep_started");
        assertNotification(rows, "critical", "B", "pending", "출발 임박 알림", null);
        assertNotification(rows, "disruption", "C", "failed", "변경 알림", null);
        assertNotification(rows, "wellness_event", "W", "delivered", "웰니스 알림", "completed");
        assertThat(rows).allSatisfy(row -> assertThat(row).doesNotContainKey("bodyMasked"));
    }

    private void assertNotification(List<Map<String, Object>> rows, String type, String slot,
            String deliveryStatus, String body, String reaction) {
        Map<String, Object> row = rows.stream()
                .filter(candidate -> type.equals(candidate.get("notificationType")))
                .findFirst().orElseThrow();
        assertThat(row.get("slot")).isEqualTo(slot);
        assertThat(row.get("deliveryStatus")).isEqualTo(deliveryStatus);
        assertThat(row.get("body")).isEqualTo(body);
        assertThat(row.get("reaction")).isEqualTo(reaction);
    }

    private Notification saveNotification(UUID planId, String category, String type, String status,
            Instant now, String body) {
        return notificationRepository.save(Notification.builder()
                .planId(planId).notificationCategory(category).notificationType(type)
                .scheduledAt(now).sentAt("sent".equals(status) ? now : null).deliveryStatus(status)
                .bodyMasked(body).triggerReason("contract-test")
                .dedupKey(UUID.randomUUID().toString()).build());
    }

    private PlanRevision createActivePlan(UUID userId, Instant now) {
        Event event = eventRepository.save(Event.builder()
                .userId(userId).sourceType("internal").startsAt(now)
                .isAllDay(false).locationState("not_required").autoManageExcluded(false)
                .status("planned").createdAt(now).build());
        return planRevisionRepository.save(PlanRevision.builder()
                .eventId(event.getEventId()).revisionNo(1)
                .prepStartAt(now).recommendedDepartAt(now).targetArriveAt(now)
                .estimatedPrepMinutes(0).extraPrepMinutes(0).personalRoutineMinutes(0)
                .travelMinutes(0).trafficBufferMinutes(0).arrivalBufferMinutes(0)
                .feasible(true).reasons("[]").degraded("[]")
                .predictionConfidence("high").planStatus("active").calcVersion("test")
                .createdAt(now).build());
    }

    private UUID extractUserId(String accessToken) {
        String payload = accessToken.split("\\.")[1];
        String decoded = new String(java.util.Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        return UUID.fromString(JsonPath.read(decoded, "$.sub").toString());
    }

    private String signupAndLogin() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String signupBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated());
        String loginBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        String response = mockMvc.perform(post("/auth/email/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }
}
