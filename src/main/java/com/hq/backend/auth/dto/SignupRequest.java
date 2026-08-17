package com.hq.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// birthDate(만 14세 확인)는 Vium 전용 요구사항이라 뺐다 — Ensom users 테이블엔
// age_confirmed_at 컬럼 자체가 없다(V5, "Ensom 범위에 없음").
public record SignupRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8) String password
) {
}
