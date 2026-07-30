package com.example.urlshortener.web.dto;

import com.example.urlshortener.metrics.UsageMetrics;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One entry in the {@code GET /api/urls/top} ranking response (FR-6, NFR-5). */
public record TopEntryResponse(
        @JsonProperty("code") String code,
        @JsonProperty("access_count") long accessCount,
        @JsonProperty("last_accessed_at") String lastAccessedAt
) {
    public static TopEntryResponse from(UsageMetrics.RankingEntry entry) {
        return new TopEntryResponse(
                entry.code(),
                entry.count(),
                entry.lastAccess() == null ? null : entry.lastAccess().toString()
        );
    }
}
