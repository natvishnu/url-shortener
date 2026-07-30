package com.example.urlshortener.service;

import com.example.urlshortener.cache.CacheUnavailableException;
import com.example.urlshortener.cache.RedirectCache;
import com.example.urlshortener.config.UrlShortenerProperties;
import com.example.urlshortener.metrics.UsageMetrics;
import com.example.urlshortener.model.UrlRecord;
import com.example.urlshortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Background expiry reaper (§6.6, FR-4, NFR-6).
 *
 * <p>On each sweep it deactivates any URL that meets either expiry condition —
 * (a) its {@code user_expires_at} has passed, or (b) it has been inactive longer
 * than {@code INACTIVITY_WINDOW} — and evicts it from the redirect cache and both
 * ranking sets in the same pass. The row itself is soft-deleted (is_active=false),
 * not hard-deleted, so analytics are preserved (consistent with FR-2).
 *
 * <p>Redirect correctness does <em>not</em> depend on the reaper: the redirect
 * handler already returns 410 for an expired/inactive URL regardless of whether
 * the reaper has run (§6.6). The reaper is for cleanup — freeing hot-path
 * cache/index space — not for gating redirects.
 *
 * <p>Guardrail: if a single pass would deactivate more than the configured
 * percentage of currently-active URLs, the whole pass is aborted and logged, to
 * catch a misconfigured default-expiry or inactivity window before it mass-expires
 * the table.
 */
@Service
public class ReaperService {

    private static final Logger log = LoggerFactory.getLogger(ReaperService.class);

    private final UrlRepository urlRepository;
    private final UsageMetrics usageMetrics;
    private final RedirectCache redirectCache;
    private final UrlShortenerProperties properties;

    public ReaperService(UrlRepository urlRepository,
                         UsageMetrics usageMetrics,
                         RedirectCache redirectCache,
                         UrlShortenerProperties properties) {
        this.urlRepository = urlRepository;
        this.usageMetrics = usageMetrics;
        this.redirectCache = redirectCache;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${urlshortener.reaper.interval-millis}")
    public void sweep() {
        reap(Instant.now());
    }

    /**
     * Runs one reaper pass as of {@code now}. Package-visible and time-parameterized
     * so tests can drive it deterministically.
     *
     * @return the number of URLs deactivated (0 if the safety cap blocked the pass)
     */
    int reap(Instant now) {
        Duration inactivityWindow = properties.getInactivityWindow();

        List<UrlRecord> active = new ArrayList<>();
        List<UrlRecord> candidates = new ArrayList<>();
        for (UrlRecord record : urlRepository.findAll()) {
            if (!record.isActive()) {
                continue;
            }
            active.add(record);
            if (shouldReap(record, now, inactivityWindow)) {
                candidates.add(record);
            }
        }

        if (candidates.isEmpty()) {
            return 0;
        }

        if (exceedsSafetyCap(candidates.size(), active.size())) {
            log.warn("Reaper aborted: would deactivate {} of {} active URLs (>{}% safety cap). "
                            + "Check default-expiry-duration / inactivity-window configuration.",
                    candidates.size(), active.size(), properties.getReaper().getSafetyCapPercent());
            return 0;
        }

        for (UrlRecord record : candidates) {
            record.deactivate();
            urlRepository.save(record);
            evictFromCache(record.getCode());
            usageMetrics.evict(record.getCode());
        }
        log.info("Reaper deactivated {} of {} active URLs", candidates.size(), active.size());
        return candidates.size();
    }

    private boolean shouldReap(UrlRecord record, Instant now, Duration inactivityWindow) {
        // (a) user_expires_at reached — read directly off the row.
        if (record.isExpired(now)) {
            return true;
        }
        // (b) inactivity-based expiry (NFR-6): compare last-access (or creation time,
        // if never accessed) against the inactivity window.
        Instant lastActivity = usageMetrics.lastAccessFor(record.getCode())
                .orElse(record.getCreatedAt());
        return lastActivity.plus(inactivityWindow).isBefore(now);
    }

    /** Best-effort cache eviction; a cache outage must not abort the reaper pass (NFR-3). */
    private void evictFromCache(String code) {
        try {
            redirectCache.evict(code);
        } catch (CacheUnavailableException ignored) {
            // Cache down — the expired entry ages out on its own; the DB row is already
            // marked inactive, so redirects for it return 410 regardless.
        }
    }

    private boolean exceedsSafetyCap(int candidateCount, int activeCount) {
        if (activeCount == 0) {
            return false;
        }
        double fraction = (double) candidateCount / activeCount;
        return fraction * 100.0 > properties.getReaper().getSafetyCapPercent();
    }
}
