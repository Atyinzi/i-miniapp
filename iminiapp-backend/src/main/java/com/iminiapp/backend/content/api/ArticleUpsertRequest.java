package com.iminiapp.backend.content.api;

public record ArticleUpsertRequest(
        String articleType, String title, String summary, String coverUrl, String content,
        String status, Boolean pinned) {
}
