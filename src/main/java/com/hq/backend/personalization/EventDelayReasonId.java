package com.hq.backend.personalization;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

// event_delay_reason의 복합 PK(event_id, reason_code) — 한 일정에 원인 복수 기록 가능(TR-05).
@NoArgsConstructor
@AllArgsConstructor
public class EventDelayReasonId implements Serializable {

    private UUID eventId;
    private String reasonCode;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EventDelayReasonId that)) {
            return false;
        }
        return Objects.equals(eventId, that.eventId) && Objects.equals(reasonCode, that.reasonCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, reasonCode);
    }
}
