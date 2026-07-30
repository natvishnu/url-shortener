package com.example.urlshortener.service;

import com.example.urlshortener.metrics.UsageMetrics;
import com.example.urlshortener.model.UrlRecord;
import com.example.urlshortener.repository.ClickRepository;
import com.example.urlshortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Periodic self-healing job (§6.5): rebuilds the ranking sets from a full
 * aggregation of the clicks table ({@code COUNT / MAX(ts) GROUP BY code}) so that
 * any async metric update lost on the hot path is eventually corrected. Only codes
 * still active are retained, so reaped URLs don't resurface in the rankings.
 *
 * <p>Per §3.3 the rankings are only required to be eventually consistent with the
 * clicks table, which is exactly what this drift-correction pass provides; the run
 * is logged so the drift correction is observable, not silent.
 */
@Service
public class MetricsRebuildJob {

    private static final Logger log = LoggerFactory.getLogger(MetricsRebuildJob.class);

    private final ClickRepository clickRepository;
    private final UrlRepository urlRepository;
    private final UsageMetrics usageMetrics;

    public MetricsRebuildJob(ClickRepository clickRepository,
                             UrlRepository urlRepository,
                             UsageMetrics usageMetrics) {
        this.clickRepository = clickRepository;
        this.urlRepository = urlRepository;
        this.usageMetrics = usageMetrics;
    }

    @Scheduled(fixedDelayString = "${urlshortener.metrics-rebuild.interval-millis:300000}")
    public void rebuild() {
        List<String> liveCodes = urlRepository.findAll().stream()
                .filter(UrlRecord::isActive)
                .map(UrlRecord::getCode)
                .collect(Collectors.toList());
        usageMetrics.rebuildFrom(clickRepository.findAll(), liveCodes);
        log.debug("Rebuilt usage metrics from {} clicks across {} live codes",
                clickRepository.count(), liveCodes.size());
    }
}
