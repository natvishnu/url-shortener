package com.example.urlshortener.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A row in the append-only {@code clicks} table (§4.2) — the sole source of truth
 * for everything usage-related (NFR-5) and for last-access / inactivity tracking
 * (NFR-6), since the {@code urls} row itself carries no counters.
 *
 * <p>Every redirect appends exactly one of these, asynchronously and off the
 * critical path (§6.3). An insert into an append-only table is cheaper and
 * lock-free compared to updating a {@code urls} row that other requests may be
 * reading concurrently. The {@code code} column is indexed for the per-code
 * analytics query.
 *
 * <p>Accessor methods keep the terse {@code code()}/{@code ts()} names from the
 * original value type so call sites read the same; JPA uses field access.
 */
@Entity
@Table(name = "clicks", indexes = @Index(name = "idx_clicks_code", columnList = "code"))
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "ts", nullable = false, updatable = false)
    private Instant ts;

    @Column(name = "referrer", updatable = false)
    private String referrer;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    @Column(name = "ip_hash", updatable = false)
    private String ipHash;

    @Column(name = "country", updatable = false)
    private String country;

    /** Required no-arg constructor for JPA; not for application use. */
    protected ClickEvent() {
    }

    public ClickEvent(String code, Instant ts, String referrer, String userAgent, String ipHash, String country) {
        this.code = code;
        this.ts = ts;
        this.referrer = referrer;
        this.userAgent = userAgent;
        this.ipHash = ipHash;
        this.country = country;
    }

    public Long id() {
        return id;
    }

    public String code() {
        return code;
    }

    public Instant ts() {
        return ts;
    }

    public String referrer() {
        return referrer;
    }

    public String userAgent() {
        return userAgent;
    }

    public String ipHash() {
        return ipHash;
    }

    public String country() {
        return country;
    }
}
