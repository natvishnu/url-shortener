package com.example.urlshortener.service;

import com.example.urlshortener.config.UrlShortenerProperties;
import com.example.urlshortener.metrics.UsageMetrics;
import com.example.urlshortener.model.UrlRecord;
import com.example.urlshortener.repository.UrlJpaRepository;
import com.example.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ReaperService}: the two expiry conditions and the safety cap
 * (§6.6), driven against H2 with a deterministic {@code now}. The scheduled reaper
 * interval is pushed far into the future here so the background sweep never races
 * the assertions.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "urlshortener.reaper.interval-millis=3600000",
        "urlshortener.metrics-rebuild.interval-millis=3600000"
})
class ReaperServiceTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ReaperService reaper;
    @Autowired
    private UrlRepository urlRepository;
    @Autowired
    private UrlJpaRepository urlJpaRepository;
    @Autowired
    private UsageMetrics usageMetrics;
    @Autowired
    private UrlShortenerProperties properties;

    @BeforeEach
    void setUp() {
        urlJpaRepository.deleteAll();
        usageMetrics.clear();
        // Reset the shared properties bean to known defaults; individual tests tweak it.
        properties.getReaper().setSafetyCapPercent(20);
        properties.setInactivityWindow(Duration.ofDays(90));
    }

    @Test
    void deactivatesUrlPastItsUserExpiry() {
        properties.getReaper().setSafetyCapPercent(100);
        urlRepository.saveIfAbsent(new UrlRecord(
                "a", "https://example.com", null, BASE.plusSeconds(3600), BASE, true));

        int deactivated = reaper.reap(BASE.plusSeconds(7200));

        assertEquals(1, deactivated);
        assertFalse(urlJpaRepository.findById("a").orElseThrow().isActive());
    }

    @Test
    void deactivatesUrlPastInactivityWindow() {
        properties.getReaper().setSafetyCapPercent(100);
        properties.setInactivityWindow(Duration.ofDays(10));
        // Expiry is far in the future, but it was never accessed since creation.
        urlRepository.saveIfAbsent(new UrlRecord(
                "b", "https://example.com", null, BASE.plus(Duration.ofDays(365)), BASE, true));

        int deactivated = reaper.reap(BASE.plus(Duration.ofDays(11)));

        assertEquals(1, deactivated);
        assertFalse(urlJpaRepository.findById("b").orElseThrow().isActive());
    }

    @Test
    void safetyCapBlocksMassExpiry() {
        properties.getReaper().setSafetyCapPercent(20);
        // 5 of 10 active URLs are expired — 50%, which exceeds the 20% cap, so the
        // whole pass must be aborted and nothing deactivated.
        for (int i = 0; i < 5; i++) {
            urlRepository.saveIfAbsent(new UrlRecord(
                    "expired-" + i, "https://example.com", null, BASE.plusSeconds(60), BASE, true));
        }
        for (int i = 0; i < 5; i++) {
            urlRepository.saveIfAbsent(new UrlRecord(
                    "live-" + i, "https://example.com", null, BASE.plus(Duration.ofDays(365)), BASE, true));
        }

        int deactivated = reaper.reap(BASE.plusSeconds(120));

        assertEquals(0, deactivated);
        assertTrue(urlJpaRepository.findAll().stream().allMatch(UrlRecord::isActive));
    }
}
