package com.example.urlshortener.repository;

import com.example.urlshortener.model.ClickEvent;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Persistence facade for the append-only {@code clicks} table (§4.2), delegating to
 * {@link ClickJpaRepository}. In production this is a time-partitioned analytics
 * table (§6.7); here it runs against H2.
 *
 * <p>Every redirect appends one row here, asynchronously and off the critical path
 * (§6.3, §6.5). Nothing on this path ever mutates the {@code urls} row.
 */
@Repository
public class ClickRepository {

    private final ClickJpaRepository jpa;

    public ClickRepository(ClickJpaRepository jpa) {
        this.jpa = jpa;
    }

    /** Appends one click. Append-only: rows here are never updated or deleted on the hot path. */
    public void append(ClickEvent event) {
        jpa.save(event);
    }

    /** All clicks for a code, used by the per-code analytics endpoint (NFR-5). */
    public List<ClickEvent> findByCode(String code) {
        return jpa.findByCode(code);
    }

    /**
     * All clicks; used by the periodic metrics-rebuild job (§6.5), which
     * re-aggregates {@code COUNT/MAX(ts) GROUP BY code} as a self-healing measure
     * against any lost async metric update.
     */
    public List<ClickEvent> findAll() {
        return jpa.findAll();
    }

    public long count() {
        return jpa.count();
    }
}
