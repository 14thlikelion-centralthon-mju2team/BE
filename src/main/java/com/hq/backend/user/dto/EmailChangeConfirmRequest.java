package com.hq.backend.user.dto;

import jakarta.validation.constraints.NotBlank;

public record EmailChangeConfirmRequest(@NotBlank String token) {
}
