package com.example.urlshortener.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * REST controller for URL shortening operations.
 *
 * <p>Backed for now by an in-memory {@link ConcurrentHashMap}. This is a temporary
 * store and is expected to be swapped out for a real datastore later on.
 */
@RestController
@RequestMapping
public class UrlController {

    private static final int GENERATED_CODE_LENGTH = 7;
    private static final int MAX_GENERATION_ATTEMPTS = 10;
    private static final Duration DEFAULT_EXPIRY = Duration.ofDays(30);
    private static final String BASE62_ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");

    private final SecureRandom secureRandom = new SecureRandom();

    /** In-memory store for short URLs, keyed by code. To be replaced by a real datastore later. */
    private final Map<String, UrlRecord> store = new ConcurrentHashMap<>();

    @PostMapping("/api/urls")
    public ResponseEntity<CreateUrlResponse> createUrl(@RequestBody CreateUrlRequest request) {
        if (request == null || request.longUrl() == null || request.longUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "long_url is required");
        }

        String longUrl = request.longUrl().trim();
        validateLongUrl(longUrl);

        Instant now = Instant.now();
        Instant expiresAt = resolveExpiry(request.userExpiresAt(), now);

        String code;
        String customAlias = request.customAlias();
        if (customAlias != null && !customAlias.isBlank()) {
            String alias = customAlias.trim();
            if (!ALIAS_PATTERN.matcher(alias).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "custom_alias must be 3-32 characters of letters, digits, '-' or '_'");
            }
            UrlRecord record = new UrlRecord(alias, longUrl, now, expiresAt, true);
            if (store.putIfAbsent(alias, record) != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "custom_alias already in use");
            }
            code = alias;
        } else {
            code = generateUniqueCode(longUrl, now, expiresAt);
        }

        UrlRecord stored = store.get(code);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CreateUrlResponse.from(stored));
    }

    @DeleteMapping("/api/urls/{code}")
    public ResponseEntity<Void> deleteUrl(@PathVariable String code) {
        UrlRecord record = store.get(code);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown code");
        }
        record.markInactive();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        UrlRecord record = store.get(code);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown code");
        }
        if (!record.isActive() || record.isExpired(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "url expired or inactive");
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", record.longUrl())
                .build();
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

    private Instant resolveExpiry(String userExpiresAt, Instant now) {
        if (userExpiresAt == null || userExpiresAt.isBlank()) {
            return now.plus(DEFAULT_EXPIRY);
        }
        try {
            Instant parsed = Instant.parse(userExpiresAt.trim());
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

    private String generateUniqueCode(String longUrl, Instant now, Instant expiresAt) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = randomCode(GENERATED_CODE_LENGTH);
            UrlRecord record = new UrlRecord(candidate, longUrl, now, expiresAt, true);
            if (store.putIfAbsent(candidate, record) == null) {
                return candidate;
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "unable to generate a unique code, please retry");
    }

    private String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62_ALPHABET.charAt(secureRandom.nextInt(BASE62_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Internal in-memory representation of a shortened URL entry. */
    private static final class UrlRecord {
        private final String code;
        private final String longUrl;
        private final Instant createdAt;
        private final Instant expiresAt;
        private volatile boolean active;

        UrlRecord(String code, String longUrl, Instant createdAt, Instant expiresAt, boolean active) {
            this.code = code;
            this.longUrl = longUrl;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.active = active;
        }

        String code() {
            return code;
        }

        String longUrl() {
            return longUrl;
        }

        Instant createdAt() {
            return createdAt;
        }

        Instant expiresAt() {
            return expiresAt;
        }

        boolean isActive() {
            return active;
        }

        void markInactive() {
            this.active = false;
        }

        boolean isExpired(Instant now) {
            return expiresAt != null && !now.isBefore(expiresAt);
        }
    }

    /** Request payload for creating a shortened URL. */
    public record CreateUrlRequest(
            @JsonProperty("long_url") String longUrl,
            @JsonProperty("custom_alias") String customAlias,
            @JsonProperty("user_expires_at") String userExpiresAt
    ) {
    }

    /** Response payload describing a created shortened URL. */
    public record CreateUrlResponse(
            @JsonProperty("code") String code,
            @JsonProperty("long_url") String longUrl,
            @JsonProperty("active") boolean active,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("expires_at") String expiresAt
    ) {
        static CreateUrlResponse from(UrlRecord record) {
            return new CreateUrlResponse(
                    record.code(),
                    record.longUrl(),
                    record.isActive(),
                    record.createdAt().toString(),
                    record.expiresAt() == null ? null : record.expiresAt().toString()
            );
        }
    }
}