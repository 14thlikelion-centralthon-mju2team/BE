package com.hq.backend.auth.dto;

import java.util.UUID;

public record SignupResponse(UUID id, String email, String provider) {
}
