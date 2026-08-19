package com.hq.backend.user.dto;

import jakarta.validation.constraints.NotBlank;

public record LinkProviderRequest(
        @NotBlank String provider,
        @NotBlank String providerToken
) {
}
