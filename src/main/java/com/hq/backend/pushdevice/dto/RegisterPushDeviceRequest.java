package com.hq.backend.pushdevice.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hq.backend.pushdevice.Platform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RegisterPushDeviceRequest(
        @NotNull UUID installationId,
        @JsonProperty("currentToken") @JsonAlias("token") @NotBlank String currentToken,
        @NotNull Platform platform
) {
}
