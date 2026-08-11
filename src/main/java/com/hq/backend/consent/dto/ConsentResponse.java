package com.hq.backend.consent.dto;

import com.hq.backend.consent.ConsentType;
import java.time.Instant;

public record ConsentResponse(
        Long id,
        ConsentType consentType,
        Boolean agreed,
        Instant recordedAt
) {
}
