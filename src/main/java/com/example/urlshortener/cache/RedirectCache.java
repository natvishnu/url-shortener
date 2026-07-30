package com.example.urlshortener.cache;

import com.example.urlshortener.model.UrlRecord;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for the Redis cache-aside layer in front of the database on the
 * redirect hot path (§6.3). A cache hit is a single fast lookup that keeps the
 * redirect handler's own work well under the latency budget (NFR-2).
 *
 * <p>Reliability contract (NFR-3, §6.4): the cache is <em>never</em> a hard
 * dependency for a redirect. When it is unavailable, every operation throws
 * {@link CacheUnavailableException}; callers treat reads as a miss (fall back to the
 * DB) and writes/evicts as best-effort no-ops, so the redirect path degrades to
 * "slower but still available" rather than failing — the design's explicit
 * availability-over-consistency trade-off.
 *
 * <p>{@link #simulateOutage()} / {@link #restore()} exist so the NFR-3 chaos test can
 * kill the cache mid-run and assert redirects still succeed via DB fallback (§7).
 */
@Component
public class RedirectCache {

    /** The hot working set (code → row). In production this is sharded Redis (§6.7). */
    private final ConcurrentHashMap<String, UrlRecord> cache = new ConcurrentHashMap<>();

    /** Whether the cache is reachable; flipped by the chaos controls below. */
    private volatile boolean available = true;

    /**
     * Looks up a cached row.
     *
     * @return the row if present, or empty on a miss
     * @throws CacheUnavailableException if the cache is unreachable
     */
    public Optional<UrlRecord> get(String code) {
        ensureAvailable();
        return Optional.ofNullable(cache.get(code));
    }

    /**
     * Warms the cache with a row (write-through on create, or on a read miss).
     *
     * @throws CacheUnavailableException if the cache is unreachable
     */
    public void put(UrlRecord record) {
        ensureAvailable();
        cache.put(record.getCode(), record);
    }

    /**
     * Evicts a row (on delete or reaper expiry, §6.6).
     *
     * @throws CacheUnavailableException if the cache is unreachable
     */
    public void evict(String code) {
        ensureAvailable();
        cache.remove(code);
    }

    /** Clears the cache entirely; used to reset state in tests. */
    public void clear() {
        cache.clear();
    }

    private void ensureAvailable() {
        if (!available) {
            throw new CacheUnavailableException("redirect cache unavailable");
        }
    }

    // --- chaos controls (NFR-3 fallback testing, §7) ---

    /** Simulates a cache outage: subsequent operations throw until {@link #restore()}. */
    public void simulateOutage() {
        this.available = false;
    }

    /** Restores the cache after a simulated outage. */
    public void restore() {
        this.available = true;
    }

    public boolean isAvailable() {
        return available;
    }
}
