package com.hq.backend.pushdevice.dto;

import com.hq.backend.pushdevice.Platform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RegisterPushDeviceRequest(
        @NotNull UUID installationId,
        @NotBlank String token,
        @NotNull Platform platform
) {
}
