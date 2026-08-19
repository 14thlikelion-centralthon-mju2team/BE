package com.hq.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordResetExecuteRequest(@NotBlank String token, @NotBlank String newPassword) {
}
