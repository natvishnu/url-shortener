package com.example.urlshortener.web;

import com.example.urlshortener.metrics.RankingType;
import com.example.urlshortener.metrics.UsageMetrics;
import com.example.urlshortener.model.ClickEvent;
import com.example.urlshortener.model.UrlRecord;
import com.example.urlshortener.repository.ClickRepository;
import com.example.urlshortener.repository.UrlRepository;
import com.example.urlshortener.service.UrlService;
import com.example.urlshortener.web.dto.AnalyticsResponse;
import com.example.urlshortener.web.dto.CreateUrlRequest;
import com.example.urlshortener.web.dto.CreateUrlResponse;
import com.example.urlshortener.web.dto.TopEntryResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST surface for the URL Shortener (§5). Thin: request/response mapping only;
 * all creation, deletion, resolution, and analytics logic lives in the service and
 * metrics layers.
 *
 * <p>Route note: {@code GET /{code}} matches only single-segment paths, so it never
 * shadows the multi-segment {@code /api/urls/...} routes. Custom aliases cannot take
 * a segment like {@code api} anyway — it is on the reserved-word blocklist (§6.2).
 */
@RestController
public class UrlController {

    private static final int MAX_RECENT_CLICKS = 20;

    private final UrlService urlService;
    private final UsageMetrics usageMetrics;
    private final ClickRepository clickRepository;
    private final UrlRepository urlRepository;

    public UrlController(UrlService urlService,
                         UsageMetrics usageMetrics,
                         ClickRepository clickRepository,
                         UrlRepository urlRepository) {
        this.urlService = urlService;
        this.usageMetrics = usageMetrics;
        this.clickRepository = clickRepository;
        this.urlRepository = urlRepository;
    }

    /** FR-1 Create, FR-5 Custom alias. */
    @PostMapping("/api/urls")
    public ResponseEntity<CreateUrlResponse> createUrl(@RequestBody(required = false) CreateUrlRequest request,
                                                       HttpServletRequest http) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        // owner_id is derived from an authenticated principal in a real deployment;
        // Create requires no auth here (§8), so anonymous URLs have a null owner.
        String ownerId = http.getUserPrincipal() == null ? null : http.getUserPrincipal().getName();
        UrlRecord record = urlService.create(
                request.longUrl(), request.customAlias(), request.userExpiresAt(), ownerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateUrlResponse.from(record));
    }

    /** FR-2 Delete (soft delete). */
    @DeleteMapping("/api/urls/{code}")
    public ResponseEntity<Void> deleteUrl(@PathVariable String code) {
        urlService.delete(code);
        return ResponseEntity.noContent().build();
    }

    /** FR-6, NFR-5: ranked listing served from the maintained metrics view. */
    @GetMapping("/api/urls/top")
    public List<TopEntryResponse> top(@RequestParam("by") String by,
                                      @RequestParam(value = "limit", defaultValue = "10") int limit) {
        if (limit <= 0 || limit > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 1000");
        }
        RankingType type;
        try {
            type = RankingType.fromParam(by);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return usageMetrics.top(type, limit).stream()
                .map(TopEntryResponse::from)
                .collect(Collectors.toList());
    }

    /** NFR-5: per-code analytics derived from the clicks stream. */
    @GetMapping("/api/urls/{code}/analytics")
    public AnalyticsResponse analytics(@PathVariable String code) {
        UrlRecord record = urlRepository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown code"));

        List<ClickEvent> clicks = clickRepository.findByCode(code);

        Map<String, Long> referrers = clicks.stream()
                .collect(Collectors.groupingBy(
                        c -> c.referrer() == null ? "(direct)" : c.referrer(),
                        Collectors.counting()));

        List<String> recentClicks = clicks.stream()
                .sorted(Comparator.comparing(ClickEvent::ts).reversed())
                .limit(MAX_RECENT_CLICKS)
                .map(c -> c.ts().toString())
                .collect(Collectors.toList());

        return new AnalyticsResponse(
                code,
                record.isActive(),
                record.getCreatedAt().toString(),
                record.getUserExpiresAt().toString(),
                usageMetrics.accessCountFor(code),
                usageMetrics.lastAccessFor(code).map(Object::toString).orElse(null),
                new LinkedHashMap<>(referrers),
                recentClicks);
    }

    /** FR-3 Redirect, FR-4 Expiry enforcement. Records the click asynchronously (§6.3). */
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest http) {
        UrlRecord record = urlService.resolve(code);

        // Fire-and-forget: the click write is off the critical path, so the caller
        // gets the 302 before the analytics append is even durable.
        urlService.recordClick(
                code,
                http.getHeader(HttpHeaders.REFERER),
                http.getHeader(HttpHeaders.USER_AGENT),
                http.getRemoteAddr(),
                null);

        // Set the Location header verbatim rather than via URI.create(...), which would
        // reject otherwise-storable long URLs containing characters invalid in a URI.
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, record.getLongUrl())
                .build();
    }
}
