package com.hq.backend.consent.dto;

import com.hq.backend.consent.ConsentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConsentRequest(
        @NotNull ConsentType consentType,
        @NotNull Boolean agreed,
        @NotBlank String policyVersion
) {
}
