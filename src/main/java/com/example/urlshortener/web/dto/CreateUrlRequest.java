package com.example.urlshortener.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for {@code POST /api/urls} (FR-1, FR-5).
 *
 * @param longUrl       target URL to shorten; required
 * @param customAlias   optional user-chosen alias used verbatim as the code (§6.2)
 * @param userExpiresAt optional ISO-8601 expiry; defaults to DEFAULT_EXPIRY_DURATION (FR-4)
 */
public record CreateUrlRequest(
        @JsonProperty("long_url") String longUrl,
        @JsonProperty("custom_alias") String customAlias,
        @JsonProperty("user_expires_at") String userExpiresAt
) {
}
