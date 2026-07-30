package com.example.urlshortener.metrics;

/**
 * The rankings exposed by {@code GET /api/urls/top?by=...} (FR-6, NFR-5).
 *
 * <p>{@link #MOST_USED} and {@link #LEAST_USED} are the same by-access-count sorted
 * set read from opposite ends; {@link #LRU} reads the by-last-access sorted set
 * ascending (§6.5). "Frequently used" is treated as equivalent to "most used" over
 * the observed lifetime (§8).
 */
public enum RankingType {
    MOST_USED,
    LEAST_USED,
    LRU;

    /** Parses the {@code by} query parameter; throws {@link IllegalArgumentException} if unknown. */
    public static RankingType fromParam(String by) {
        if (by == null) {
            throw new IllegalArgumentException("by is required");
        }
        return switch (by.trim().toLowerCase()) {
            case "most_used", "frequently_used" -> MOST_USED;
            case "least_used" -> LEAST_USED;
            case "lru", "least_recently_used" -> LRU;
            default -> throw new IllegalArgumentException(
                    "by must be one of: most_used, least_used, lru");
        };
    }
}
