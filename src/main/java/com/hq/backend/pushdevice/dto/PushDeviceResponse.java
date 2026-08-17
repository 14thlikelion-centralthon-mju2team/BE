package com.hq.backend.pushdevice.dto;

import java.time.Instant;
import java.util.UUID;

public record PushDeviceResponse(
        UUID pushDeviceId,
        UUID installationId,
        String platform,
        Instant lastSeenAt
) {
}
