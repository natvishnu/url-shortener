package com.example.urlshortener.model;

import java.util.Objects;

/**
 * Represents a shortened URL entry.
 *
 * <p>This class is insert-mostly: once constructed, every field is immutable
 * except {@link #isActive}, which may only be changed via {@link #deactivate()}.
 *
 * <p>Note: access statistics (e.g. access count, last accessed timestamp) are
 * intentionally NOT tracked on this row. They are derived from an append-only
 * click log stored elsewhere.
 */
public final class UrlRecord {

    private final String code;
    private final String longUrl;
    private final String ownerId;
    private final long userExpiresAt;
    private final long createdAt;
    private boolean isActive;

    /**
     * Creates a new UrlRecord.
     *
     * @param code           the short code identifying this URL, must not be null
     * @param longUrl        the original long URL, must not be null
     * @param ownerId        the identifier of the owning user, may be null for anonymous links
     * @param userExpiresAt  the epoch millis timestamp at which the user-specified
     *                       expiration occurs; required, must not be null
     * @param createdAt      the epoch millis timestamp at which this record was created
     * @param isActive       whether this record is currently active
     */
    public UrlRecord(String code,
                      String longUrl,
                      String ownerId,
                      long userExpiresAt,
                      long createdAt,
                      boolean isActive) {
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.longUrl = Objects.requireNonNull(longUrl, "longUrl must not be null");
        this.ownerId = ownerId;
        this.userExpiresAt = userExpiresAt;
        this.createdAt = createdAt;
        this.isActive = isActive;
    }

    public String getCode() {
        return code;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public long getUserExpiresAt() {
        return userExpiresAt;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return isActive;
    }

    /**
     * Marks this record as inactive. This is the only mutation permitted
     * on a UrlRecord after construction.
     */
    public void deactivate() {
        this.isActive = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UrlRecord)) {
            return false;
        }
        UrlRecord that = (UrlRecord) o;
        return userExpiresAt == that.userExpiresAt
                && createdAt == that.createdAt
                && isActive == that.isActive
                && Objects.equals(code, that.code)
                && Objects.equals(longUrl, that.longUrl)
                && Objects.equals(ownerId, that.ownerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, longUrl, ownerId, userExpiresAt, createdAt, isActive);
    }

    @Override
    public String toString() {
        return "UrlRecord{"
                + "code='" + code + '\''
                + ", longUrl='" + longUrl + '\''
                + ", ownerId='" + ownerId + '\''
                + ", userExpiresAt=" + userExpiresAt
                + ", createdAt=" + createdAt
                + ", isActive=" + isActive
                + '}';
    }
}