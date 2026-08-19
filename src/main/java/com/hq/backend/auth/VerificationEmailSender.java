package com.hq.backend.auth;

/** Delivers the raw one-time verification link. Implementations must never persist or log the raw token. */
public interface VerificationEmailSender {

    boolean isAvailable();

    void sendVerificationLink(String recipientEmail, String verificationLink);
}
