package com.hq.backend.auth.dto;

import java.util.UUID;

/** Signup never issues session tokens; email ownership must be verified first. */
public record SignupResponse(
        UUID id,
        String email,
        boolean emailVerified,
        boolean verificationSent
) {
}
