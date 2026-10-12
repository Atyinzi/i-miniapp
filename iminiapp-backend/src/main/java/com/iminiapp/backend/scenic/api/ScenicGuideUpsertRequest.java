package com.iminiapp.backend.scenic.api;

public record ScenicGuideUpsertRequest(
        String contentType,
        String title,
        String summary,
        String coverUrl,
        String contentUrl,
        String contentText,
        Integer durationSeconds,
        Integer sortOrder,
        String status) {
}
