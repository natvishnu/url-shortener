package com.example.urlshortener.metrics;

import com.example.urlshortener.model.ClickEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for the two Redis sorted sets that sit on top of the clicks
 * stream (§6.5):
 * <ul>
 *   <li>a <em>by-access-count</em> set (code &rarr; count), maintained with the
 *       equivalent of {@code ZINCRBY} on each click;</li>
 *   <li>a <em>by-last-access</em> set (code &rarr; last-access timestamp),
 *       maintained with the equivalent of {@code ZADD}.</li>
 * </ul>
 *
 * <p>These give low-latency "most used / least used / least recently used" reads
 * (FR-6, NFR-5) without ever touching the {@code urls} row or scanning the clicks
 * table. Per §3.3 they are allowed to be eventually consistent with the clicks
 * table; {@link #rebuildFrom} is the self-healing full re-aggregation described in
 * §6.5.
 */
@Component
public class UsageMetrics {

    private final ConcurrentHashMap<String, Long> accessCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastAccess = new ConcurrentHashMap<>();

    /**
     * Records one access. Called by the async click worker (§6.5), never on the
     * redirect critical path itself.
     */
    public void recordAccess(String code, Instant ts) {
        accessCount.merge(code, 1L, Long::sum);
        lastAccess.merge(code, ts, (existing, incoming) -> incoming.isAfter(existing) ? incoming : existing);
    }

    /** Drops a code from both sets — used by the reaper when it removes a URL (§6.6). */
    public void evict(String code) {
        accessCount.remove(code);
        lastAccess.remove(code);
    }

    /** Clears both sets. Mirrors flushing the Redis layer; used to reset state in tests. */
    public void clear() {
        accessCount.clear();
        lastAccess.clear();
    }

    public long accessCountFor(String code) {
        return accessCount.getOrDefault(code, 0L);
    }

    public Optional<Instant> lastAccessFor(String code) {
        return Optional.ofNullable(lastAccess.get(code));
    }

    /**
     * Reads the top {@code limit} entries for the requested ranking. O(N log N) here
     * over the in-memory maps; O(log N) range reads against real Redis sorted sets.
     */
    public List<RankingEntry> top(RankingType type, int limit) {
        List<RankingEntry> entries = switch (type) {
            case MOST_USED -> rankByCount(Comparator.reverseOrder());
            case LEAST_USED -> rankByCount(Comparator.naturalOrder());
            case LRU -> rankByLastAccess();
        };
        return entries.size() > limit ? entries.subList(0, limit) : entries;
    }

    private List<RankingEntry> rankByCount(Comparator<Long> order) {
        List<RankingEntry> list = new ArrayList<>();
        for (Map.Entry<String, Long> e : accessCount.entrySet()) {
            list.add(new RankingEntry(e.getKey(), e.getValue(), lastAccess.get(e.getKey())));
        }
        // Non-zero only: "least used" means lowest non-zero count (NFR-5); a code with
        // no clicks never appears in accessCount, so it is naturally excluded.
        list.sort(Comparator.comparing(RankingEntry::count, order)
                .thenComparing(RankingEntry::code));
        return list;
    }

    private List<RankingEntry> rankByLastAccess() {
        List<RankingEntry> list = new ArrayList<>();
        for (Map.Entry<String, Instant> e : lastAccess.entrySet()) {
            list.add(new RankingEntry(e.getKey(), accessCount.getOrDefault(e.getKey(), 0L), e.getValue()));
        }
        // Ascending by last-access: the oldest access is the least-recently-used.
        list.sort(Comparator.comparing(RankingEntry::lastAccess, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RankingEntry::code));
        return list;
    }

    /**
     * Rebuilds both sets from a full aggregation of the clicks stream
     * ({@code COUNT / MAX(ts) GROUP BY code}), matching the periodic self-healing
     * job in §6.5. Only codes still present in {@code liveCodes} are retained, so a
     * reaped URL does not resurface in the rankings after a rebuild.
     */
    public synchronized void rebuildFrom(List<ClickEvent> clicks, Collection<String> liveCodes) {
        ConcurrentHashMap<String, Long> counts = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Instant> latest = new ConcurrentHashMap<>();
        for (ClickEvent c : clicks) {
            if (!liveCodes.contains(c.code())) {
                continue;
            }
            counts.merge(c.code(), 1L, Long::sum);
            latest.merge(c.code(), c.ts(), (a, b) -> b.isAfter(a) ? b : a);
        }
        accessCount.clear();
        accessCount.putAll(counts);
        lastAccess.clear();
        lastAccess.putAll(latest);
    }

    /** One entry in a ranking response. */
    public record RankingEntry(String code, long count, Instant lastAccess) {
    }
}
