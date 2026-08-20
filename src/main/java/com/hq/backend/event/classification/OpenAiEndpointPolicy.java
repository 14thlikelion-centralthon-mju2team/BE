package com.hq.backend.event.classification;

import java.net.URI;

final class OpenAiEndpointPolicy {

    private static final String APPROVED_HOST = "api.openai.com";
    private static final String APPROVED_PATH = "/v1";

    private OpenAiEndpointPolicy() {
    }

    static boolean isApproved(URI baseUrl) {
        return baseUrl != null
                && "https".equalsIgnoreCase(baseUrl.getScheme())
                && APPROVED_HOST.equalsIgnoreCase(baseUrl.getHost())
                && (baseUrl.getPort() == -1 || baseUrl.getPort() == 443)
                && baseUrl.getUserInfo() == null
                && APPROVED_PATH.equals(baseUrl.getPath())
                && baseUrl.getQuery() == null
                && baseUrl.getFragment() == null;
    }

    static boolean isApproved(String baseUrl) {
        try {
            return baseUrl != null && isApproved(URI.create(baseUrl));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
