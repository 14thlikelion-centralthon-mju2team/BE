package com.hq.backend.user.dto;

import java.util.List;

public record AccountDeletionResponse(List<String> deleted, List<String> retained, String retentionReason) {
}
