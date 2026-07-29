package com.example.urlshortener.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller exposing URL shortening operations backed by an in-memory
 * {@link ConcurrentHashMap} store. This store is a temporary placeholder and
 * is expected to be swapped for a real datastore in a later change.
 */
@RestController
public class UrlController {

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 7;
    private static final int MAX_CODE_GENERATION_ATTEMPTS = 10;
    private static final long DEFAULT_EXPIRY_DAYS = 30;

    private final Map<String, UrlEntry> store = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @PostMapping("/api/urls")
    public ResponseEntity<CreateUrlResponse> createUrl(@RequestBody CreateUrlRequest request) {
        String longUrl = validateAndNormalizeLongUrl(request.longUrl());

        Instant now = Instant.now();
        Instant expiresAt = resolveExpiry(request.userExpiresAt(), now);

        String code;
        if (request.customAlias() != null && !request.customAlias().isBlank()) {
            code = validateAlias(request.customAlias());
            UrlEntry existing = store.putIfAbsent(code, new UrlEntry(longUrl, now, expiresAt, true));
            if (existing != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Alias already in use");
            }
        } else {
            code = generateUniqueCode(longUrl, now, expiresAt);
        }

        UrlEntry entry = store.get(code);
        CreateUrlResponse response = new CreateUrlResponse(
                code, entry.longUrl(), entry.createdAt(), entry.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/api/urls/{code}")
    public ResponseEntity<Void> deleteUrl(@PathVariable String code) {
        UrlEntry entry = store.get(code);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown code");
        }
        store.put(code, entry.deactivated());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        UrlEntry entry = store.get(code);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown code");
        }

        boolean expired = entry.expiresAt() != null && Instant.now().isAfter(entry.expiresAt());
        if (!entry.active() || expired) {
            throw new ResponseStatusException(HttpStatus.GONE, "URL is no longer available");
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, entry.longUrl())
                .build();
    }

    private String validateAndNormalizeLongUrl(String longUrl) {
        if (longUrl == null || longUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "long_url is required");
        }
        try {
            URI uri = new URI(longUrl);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "long_url must be a valid http(s) URL");
            }
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "long_url must be a valid http(s) URL");
        }
        return longUrl;
    }

    private String validateAlias(String alias) {
        if (!alias.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "custom_alias contains invalid characters");
        }
        return alias;
    }

    private Instant resolveExpiry(Instant userExpiresAt, Instant now) {
        if (userExpiresAt == null) {
            return now.plus(DEFAULT_EXPIRY_DAYS, ChronoUnit.DAYS);
        }
        if (!userExpiresAt.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user_expires_at must be in the future");
        }
        return userExpiresAt;
    }

    private String generateUniqueCode(String longUrl, Instant now, Instant expiresAt) {
        for (int attempt = 0; attempt < MAX_CODE_GENERATION_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            UrlEntry existing = store.putIfAbsent(candidate, new UrlEntry(longUrl, now, expiresAt, true));
            if (existing == null) {
                return candidate;
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to generate a unique code");
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private record UrlEntry(String longUrl, Instant createdAt, Instant expiresAt, boolean active) {
        UrlEntry deactivated() {
            return new UrlEntry(longUrl, createdAt, expiresAt, false);
        }
    }

    public record CreateUrlRequest(
            @JsonProperty("long_url") String longUrl,
            @JsonProperty("custom_alias") String customAlias,
            @JsonProperty("user_expires_at") Instant userExpiresAt) {
    }

    public record CreateUrlResponse(
            String code,
            @JsonProperty("long_url") String longUrl,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("expires_at") Instant expiresAt) {
    }
}