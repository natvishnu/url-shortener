package com.example.urlshortener.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * A row in the {@code urls} table (§4.1), persisted to the database.
 *
 * <p>The table is <strong>insert-mostly</strong> by design: once created, nothing
 * about a row is rewritten on the redirect hot path. The only field that ever
 * mutates after construction is {@link #active}, which is flipped to {@code false}
 * exactly once on delete or by the expiry reaper (§6.6) — a rare lifecycle write,
 * never something a redirect does. This keeps the redirect path a pure read, which
 * directly serves NFR-2 (latency) and NFR-4 (scale).
 *
 * <p>Consequently there are deliberately <em>no</em> {@code access_count},
 * {@code last_accessed_at}, or {@code updated_at} columns here — they would force a
 * write on every redirect. Usage metrics and last-access tracking (NFR-5, NFR-6)
 * are derived entirely from the append-only clicks table and the metrics layer
 * built on top of it, never from this row.
 */
@Entity
@Table(name = "urls")
public class UrlRecord {

    /** Base62 short code; the primary key and the uniqueId (NFR-1). */
    @Id
    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    /** Target URL; immutable once created (no Update in this iteration). */
    @Column(name = "long_url", nullable = false, updatable = false, length = 2048)
    private String longUrl;

    /**
     * Creator/owner, for scoping delete. Nullable because Create (FR-1) does not
     * require authentication — anonymous, unowned URLs are allowed (§8).
     */
    @Column(name = "owner_id", updatable = false)
    private String ownerId;

    /**
     * Always populated (NOT NULL): the user-supplied expiry if given at creation,
     * otherwise {@code createdAt + DEFAULT_EXPIRY_DURATION} (FR-4). Never null, so
     * the redirect and reaper paths never need a null-check special case.
     */
    @Column(name = "user_expires_at", nullable = false, updatable = false)
    private Instant userExpiresAt;

    /** Set once at insert; there is no {@code updated_at} because nothing else mutates. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The one field that mutates after creation — flipped to false on delete/expiry. */
    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** Required no-arg constructor for JPA; not for application use. */
    protected UrlRecord() {
    }

    public UrlRecord(String code,
                     String longUrl,
                     String ownerId,
                     Instant userExpiresAt,
                     Instant createdAt,
                     boolean active) {
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.longUrl = Objects.requireNonNull(longUrl, "longUrl must not be null");
        this.ownerId = ownerId;
        this.userExpiresAt = Objects.requireNonNull(userExpiresAt, "userExpiresAt must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.active = active;
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

    public Instant getUserExpiresAt() {
        return userExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Marks this record inactive. This is the only mutation permitted on a
     * UrlRecord after construction (delete, FR-2; or reaper expiry, §6.6).
     */
    public void deactivate() {
        this.active = false;
    }

    /**
     * Whether this row's expiry timestamp has been reached as of {@code now}.
     * Enforced on every redirect regardless of whether the reaper has run yet (§6.6).
     */
    public boolean isExpired(Instant now) {
        return !now.isBefore(userExpiresAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UrlRecord that)) {
            return false;
        }
        // Identity is the primary key (the short code).
        return Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return "UrlRecord{"
                + "code='" + code + '\''
                + ", longUrl='" + longUrl + '\''
                + ", ownerId='" + ownerId + '\''
                + ", userExpiresAt=" + userExpiresAt
                + ", createdAt=" + createdAt
                + ", active=" + active
                + '}';
    }
}
