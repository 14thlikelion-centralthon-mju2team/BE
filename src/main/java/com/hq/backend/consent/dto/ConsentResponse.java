package com.hq.backend.consent.dto;

import com.hq.backend.consent.ConsentType;
import java.time.Instant;
import java.util.UUID;

public record ConsentResponse(
        UUID id,
        ConsentType consentType,
        Boolean agreed,
        Instant recordedAt
) {
}
