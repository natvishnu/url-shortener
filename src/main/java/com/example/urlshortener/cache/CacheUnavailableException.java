package com.example.urlshortener.cache;

/**
 * Signals that the redirect cache (Redis, in production) could not be reached.
 * Callers on the redirect path catch this and fall back to the database, so a cache
 * outage degrades latency but never availability (NFR-3, §6.4).
 */
public class CacheUnavailableException extends RuntimeException {

    public CacheUnavailableException(String message) {
        super(message);
    }
}
