package com.example.urlshortener.service;

import com.example.urlshortener.cache.CacheUnavailableException;
import com.example.urlshortener.cache.RedirectCache;
import com.example.urlshortener.config.UrlShortenerProperties;
import com.example.urlshortener.id.SnowflakeCodeGenerator;
import com.example.urlshortener.metrics.UsageMetrics;
import com.example.urlshortener.model.ClickEvent;
import com.example.urlshortener.model.UrlRecord;
import com.example.urlshortener.repository.ClickRepository;
import com.example.urlshortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Core service for the URL Shortener. Owns creation, deletion, and resolution of
 * short URLs, plus the fire-and-forget recording of click analytics.
 *
 * <p>The redirect path ({@link #resolve}) performs <em>zero</em> writes to the
 * {@code urls} row; the click event is appended asynchronously (§6.3), so the
 * caller gets its redirect before the analytics write is even durable.
 */
@Service
public class UrlService {

    private static final Logger log = LoggerFactory.getLogger(UrlService.class);

    private static final int MAX_ALIAS_LENGTH = 32;
    private static final int MIN_ALIAS_LENGTH = 3;
    private static final int MAX_GENERATION_ATTEMPTS = 10;
    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");

    private final UrlRepository urlRepository;
    private final ClickRepository clickRepository;
    private final UsageMetrics usageMetrics;
    private final RedirectCache redirectCache;
    private final SnowflakeCodeGenerator codeGenerator;
    private final UrlShortenerProperties properties;
    private final Set<String> reservedAliases;

    public UrlService(UrlRepository urlRepository,
                      ClickRepository clickRepository,
                      UsageMetrics usageMetrics,
                      RedirectCache redirectCache,
                      SnowflakeCodeGenerator codeGenerator,
                      UrlShortenerProperties properties) {
        this.urlRepository = urlRepository;
        this.clickRepository = clickRepository;
        this.usageMetrics = usageMetrics;
        this.redirectCache = redirectCache;
        this.codeGenerator = codeGenerator;
        this.properties = properties;
        this.reservedAliases = properties.getReservedAliases().stream()
                .map(s -> s.toLowerCase())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Creates a short URL (FR-1, FR-5). Validates the long URL and, if supplied, the
     * custom alias (charset, length, reserved words); otherwise mints a Snowflake
     * base62 code. Expiry defaults to {@code now + DEFAULT_EXPIRY_DURATION} when the
     * caller omits it, so the row's expiry column is always populated (FR-4).
     */
    public UrlRecord create(String longUrlRaw, String customAlias, String userExpiresAtRaw, String ownerId) {
        if (longUrlRaw == null || longUrlRaw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "long_url is required");
        }
        String longUrl = longUrlRaw.trim();
        validateLongUrl(longUrl);

        Instant now = Instant.now();
        Instant expiresAt = resolveExpiry(userExpiresAtRaw, now);

        if (customAlias != null && !customAlias.isBlank()) {
            return createWithAlias(customAlias.trim(), longUrl, ownerId, expiresAt, now);
        }
        return createWithGeneratedCode(longUrl, ownerId, expiresAt, now);
    }

    private UrlRecord createWithAlias(String alias, String longUrl, String ownerId,
                                      Instant expiresAt, Instant now) {
        validateAlias(alias);
        UrlRecord record = new UrlRecord(alias, longUrl, ownerId, expiresAt, now, true);
        if (!urlRepository.saveIfAbsent(record)) {
            // Same unique index that generated codes are checked against (§6.1); a
            // conflicting alias is rejected outright, never silently deduplicated.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "custom_alias already in use");
        }
        warmCache(record);
        return record;
    }

    private UrlRecord createWithGeneratedCode(String longUrl, String ownerId,
                                              Instant expiresAt, Instant now) {
        // Snowflake ids are unique by construction, so a collision here is effectively
        // impossible; the retry loop is a belt-and-suspenders guard only.
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String code = codeGenerator.nextCode();
            UrlRecord record = new UrlRecord(code, longUrl, ownerId, expiresAt, now, true);
            if (urlRepository.saveIfAbsent(record)) {
                warmCache(record);
                return record;
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "unable to generate a unique code, please retry");
    }

    /**
     * Soft-deletes a short URL (FR-2): flips {@code is_active} to false and evicts it
     * from the redirect cache and both ranking sets. The row and its click history are
     * preserved for audit.
     */
    public void delete(String code) {
        UrlRecord record = urlRepository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown code"));
        record.deactivate();
        urlRepository.save(record);
        evictFromCache(code);
        usageMetrics.evict(code);
    }

    /**
     * Resolves a code to its target for redirect (FR-3). Enforces expiry on every
     * request (FR-4): an unknown code is 404, an inactive/expired code is 410 Gone,
     * independent of whether the reaper has processed it yet (§6.6).
     *
     * <p>Does not itself record the click — the caller invokes {@link #recordClick}
     * asynchronously so the redirect never blocks on the analytics write (§6.3).
     */
    public UrlRecord resolve(String code) {
        UrlRecord record = lookup(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown code"));
        if (!record.isActive() || record.isExpired(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "url expired or inactive");
        }
        return record;
    }

    /**
     * Cache-aside lookup with graceful DB fallback (§6.3, §6.4, NFR-3). A cache hit is
     * a single fast lookup; on a miss the row is read from the DB and the cache is
     * warmed (write-through). If the cache is unavailable, the request falls back
     * directly to the DB rather than failing — slower, but still available. This is
     * the design's explicit availability-over-consistency posture, and the reason no
     * single component is a hard dependency for the redirect path.
     */
    private Optional<UrlRecord> lookup(String code) {
        try {
            Optional<UrlRecord> cached = redirectCache.get(code);
            if (cached.isPresent()) {
                return cached;
            }
            Optional<UrlRecord> fromDb = urlRepository.findByCode(code);
            fromDb.ifPresent(this::warmCache);
            return fromDb;
        } catch (CacheUnavailableException cacheDown) {
            log.warn("Redirect cache unavailable; serving from DB (NFR-3 fallback): {}",
                    cacheDown.getMessage());
            return urlRepository.findByCode(code);
        }
    }

    /**
     * Appends a click event and updates the ranking sets (§6.5), off the redirect
     * critical path. {@code @Async} makes this fire-and-forget; the try/catch further
     * guarantees that an analytics failure (DB hiccup, ranking error) can never
     * surface to the caller, who already received the redirect — the redirect path
     * has no hard dependency on the analytics path (NFR-3).
     */
    @Async
    public void recordClick(String code, String referrer, String userAgent, String ip, String country) {
        try {
            Instant ts = Instant.now();
            clickRepository.append(new ClickEvent(code, ts, referrer, userAgent, hashIp(ip), country));
            usageMetrics.recordAccess(code, ts);
        } catch (RuntimeException e) {
            log.warn("Failed to record click for '{}' (analytics only; redirect unaffected): {}",
                    code, e.getMessage());
        }
    }

    /** Best-effort cache warm; a cache-write failure must never fail the request (NFR-3). */
    private void warmCache(UrlRecord record) {
        try {
            redirectCache.put(record);
        } catch (CacheUnavailableException ignored) {
            // Cache down — the row is safely in the DB and will be re-cached on next read.
        }
    }

    /** Best-effort cache evict; if the cache is down the entry ages out on its own (§6.4). */
    private void evictFromCache(String code) {
        try {
            redirectCache.evict(code);
        } catch (CacheUnavailableException ignored) {
            // Cache down — accept the brief staleness window (AP over CP, §6.4).
        }
    }

    private void validateLongUrl(String longUrl) {
        try {
            URI uri = new URI(longUrl);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "long_url must be a valid absolute http(s) URL");
            }
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "long_url is not a valid URL");
        }
    }

    private void validateAlias(String alias) {
        if (!ALIAS_PATTERN.matcher(alias).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "custom_alias must be " + MIN_ALIAS_LENGTH + "-" + MAX_ALIAS_LENGTH
                            + " characters of letters, digits, '-' or '_'");
        }
        if (reservedAliases.contains(alias.toLowerCase())) {
            // Reserved-word blocklist (§6.2): aliases must not shadow the service's routes.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "custom_alias '" + alias + "' is reserved");
        }
    }

    private Instant resolveExpiry(String userExpiresAtRaw, Instant now) {
        if (userExpiresAtRaw == null || userExpiresAtRaw.isBlank()) {
            return now.plus(properties.getDefaultExpiryDuration());
        }
        try {
            Instant parsed = Instant.parse(userExpiresAtRaw.trim());
            if (!parsed.isAfter(now)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "user_expires_at must be in the future");
            }
            return parsed;
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "user_expires_at must be a valid ISO-8601 instant");
        }
    }

    /**
     * Non-reversible hash of the caller IP — the clicks table stores {@code ip_hash},
     * never a raw IP (§4.2). Not a security control, just a privacy-preserving key.
     */
    private static String hashIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        return Integer.toHexString(ip.hashCode())
                + Integer.toHexString(ip.getBytes(StandardCharsets.UTF_8).length);
    }
}
