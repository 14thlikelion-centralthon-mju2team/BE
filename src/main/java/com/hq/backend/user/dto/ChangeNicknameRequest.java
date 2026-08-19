package com.hq.backend.user.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangeNicknameRequest(@NotBlank String nickname) {
}
