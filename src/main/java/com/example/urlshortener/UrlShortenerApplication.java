package com.example.urlshortener;

import com.example.urlshortener.config.UrlShortenerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the URL Shortener service.
 *
 * <p>{@code @EnableScheduling} powers the background expiry reaper (§6.6), and
 * {@code @EnableAsync} lets the redirect hot path append click events off the
 * critical path (§6.3) so a redirect never blocks on analytics writes.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(UrlShortenerProperties.class)
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
