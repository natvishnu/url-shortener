package com.example.urlshortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Externalized configuration for the service, bound from the {@code urlshortener.*}
 * namespace (see {@code application.properties}).
 *
 * <p>Per the design (§8), two independent, system-level durations exist and are
 * tuned only here — there is no per-URL override:
 * <ul>
 *   <li>{@link #defaultExpiryDuration} — DEFAULT_EXPIRY_DURATION, applied when the
 *       user omits an expiry at creation (FR-4).</li>
 *   <li>{@link #inactivityWindow} — INACTIVITY_WINDOW, after which an un-accessed
 *       URL is auto-expired independently of its expiry date (NFR-6).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "urlshortener")
public class UrlShortenerProperties {

    /**
     * DEFAULT_EXPIRY_DURATION (FR-4): applied at creation when the caller does not
     * supply {@code user_expires_at}, so every row's expiry column is always populated.
     */
    private Duration defaultExpiryDuration = Duration.ofDays(30);

    /**
     * INACTIVITY_WINDOW (NFR-6): a URL not accessed within this window is expired by
     * the reaper even if its {@code user_expires_at} is still in the future.
     */
    private Duration inactivityWindow = Duration.ofDays(90);

    /** Worker id for the Snowflake-style code generator (§6.1); partitions ids across instances. */
    private long workerId = 1L;

    /**
     * Reserved short codes that a custom alias may not take, so aliases cannot shadow
     * the service's own routes (§6.2).
     */
    private List<String> reservedAliases = List.of("api", "admin", "top", "analytics", "health", "actuator");

    private final Reaper reaper = new Reaper();

    public Duration getDefaultExpiryDuration() {
        return defaultExpiryDuration;
    }

    public void setDefaultExpiryDuration(Duration defaultExpiryDuration) {
        this.defaultExpiryDuration = defaultExpiryDuration;
    }

    public Duration getInactivityWindow() {
        return inactivityWindow;
    }

    public void setInactivityWindow(Duration inactivityWindow) {
        this.inactivityWindow = inactivityWindow;
    }

    public long getWorkerId() {
        return workerId;
    }

    public void setWorkerId(long workerId) {
        this.workerId = workerId;
    }

    public List<String> getReservedAliases() {
        return reservedAliases;
    }

    public void setReservedAliases(List<String> reservedAliases) {
        this.reservedAliases = reservedAliases;
    }

    public Reaper getReaper() {
        return reaper;
    }

    /** Settings for the background expiry reaper (§6.6). */
    public static class Reaper {

        /** How often the reaper sweeps for expired/inactive URLs, in milliseconds. */
        private long intervalMillis = 60_000L;

        /**
         * Guardrail (§6.6): the reaper refuses to run if a single pass would deactivate
         * more than this percentage of currently-active URLs, catching a misconfigured
         * expiry/inactivity window before it mass-expires the table.
         */
        private int safetyCapPercent = 20;

        public long getIntervalMillis() {
            return intervalMillis;
        }

        public void setIntervalMillis(long intervalMillis) {
            this.intervalMillis = intervalMillis;
        }

        public int getSafetyCapPercent() {
            return safetyCapPercent;
        }

        public void setSafetyCapPercent(int safetyCapPercent) {
            this.safetyCapPercent = safetyCapPercent;
        }
    }
}
