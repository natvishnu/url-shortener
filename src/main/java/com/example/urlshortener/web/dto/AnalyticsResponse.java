package com.example.urlshortener.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Per-code analytics for {@code GET /api/urls/{code}/analytics} (NFR-5):
 * current metric standing plus a breakdown derived from the clicks stream.
 *
 * @param code           the short code
 * @param active         whether the URL is currently active
 * @param createdAt      creation timestamp
 * @param expiresAt      expiry timestamp
 * @param totalClicks    total recorded redirects (from the clicks table)
 * @param lastAccessedAt most recent access, or null if never accessed
 * @param referrers      click count grouped by referrer
 * @param recentClicks   timestamps of the most recent clicks (newest first)
 */
public record AnalyticsResponse(
        @JsonProperty("code") String code,
        @JsonProperty("active") boolean active,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("expires_at") String expiresAt,
        @JsonProperty("total_clicks") long totalClicks,
        @JsonProperty("last_accessed_at") String lastAccessedAt,
        @JsonProperty("referrers") Map<String, Long> referrers,
        @JsonProperty("recent_clicks") List<String> recentClicks
) {
}
