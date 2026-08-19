package com.hq.backend.user.dto;

import com.hq.backend.user.UserIdentity;
import java.time.Instant;
import java.util.UUID;

public record ProviderResponse(
        UUID identityId,
        String provider,
        Instant linkedAt
) {

    public static ProviderResponse from(UserIdentity identity) {
        return new ProviderResponse(
                identity.getIdentityId(),
                identity.getProvider(),
                identity.getLinkedAt());
    }
}
