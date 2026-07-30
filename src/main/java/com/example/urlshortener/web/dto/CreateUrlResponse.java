package com.example.urlshortener.web.dto;

import com.example.urlshortener.model.UrlRecord;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response body describing a created short URL. */
public record CreateUrlResponse(
        @JsonProperty("code") String code,
        @JsonProperty("long_url") String longUrl,
        @JsonProperty("active") boolean active,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("expires_at") String expiresAt
) {
    public static CreateUrlResponse from(UrlRecord record) {
        return new CreateUrlResponse(
                record.getCode(),
                record.getLongUrl(),
                record.isActive(),
                record.getCreatedAt().toString(),
                record.getUserExpiresAt().toString()
        );
    }
}
