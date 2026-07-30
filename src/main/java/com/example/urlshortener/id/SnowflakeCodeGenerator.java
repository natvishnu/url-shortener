package com.example.urlshortener.id;

import com.example.urlshortener.config.UrlShortenerProperties;
import org.springframework.stereotype.Component;

/**
 * Generates short codes as base62-encoded, monotonically-increasing distributed IDs
 * in the Snowflake style: {@code timestamp | worker-id | sequence} (§6.1).
 *
 * <p>Why this and not a hash of the long URL: uniqueness (NFR-1) holds
 * <em>by construction</em> rather than by probability. Two different long URLs — or
 * the same long URL submitted twice — always get distinct codes, and there is no
 * central counter to bottleneck or become a single point of failure.
 *
 * <p>Cross-instance safety: the worker-id (from {@link UrlShortenerProperties}) is
 * partitioned across API instances, so ids minted concurrently on different
 * instances never collide even though generation is stateless per request.
 */
@Component
public class SnowflakeCodeGenerator {

    /** Custom epoch (2024-01-01T00:00:00Z) so timestamps fit in 41 bits for decades. */
    private static final long CUSTOM_EPOCH_MILLIS = 1_704_067_200_000L;

    private static final int WORKER_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;

    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    private static final String BASE62_ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final long workerId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeCodeGenerator(UrlShortenerProperties properties) {
        long id = properties.getWorkerId();
        if (id < 0 || id > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "urlshortener.worker-id must be between 0 and " + MAX_WORKER_ID + ", got " + id);
        }
        this.workerId = id;
    }

    /** Mints the next unique short code. Thread-safe. */
    public synchronized String nextCode() {
        return base62(nextId());
    }

    private synchronized long nextId() {
        long timestamp = currentTime();

        if (timestamp < lastTimestamp) {
            // Clock moved backwards (e.g. NTP correction). Wait it out rather than
            // risk minting an id that could duplicate one already handed out.
            timestamp = waitUntil(lastTimestamp);
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence exhausted for this millisecond — advance to the next one.
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - CUSTOM_EPOCH_MILLIS) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitUntil(long targetMillis) {
        long timestamp = currentTime();
        while (timestamp < targetMillis) {
            timestamp = currentTime();
        }
        return timestamp;
    }

    // Package-visible so tests can override with a deterministic clock if needed.
    long currentTime() {
        return System.currentTimeMillis();
    }

    private static String base62(long value) {
        if (value == 0) {
            return "0";
        }
        // Treat the 63-bit positive id as unsigned; it is always >= 0 here.
        StringBuilder sb = new StringBuilder();
        long v = value;
        while (v > 0) {
            int rem = (int) (v % 62);
            sb.append(BASE62_ALPHABET.charAt(rem));
            v /= 62;
        }
        return sb.reverse().toString();
    }
}
