package com.example.urlshortener.repository;

import com.example.urlshortener.model.UrlRecord;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Persistence facade for the {@code urls} table, delegating to
 * {@link UrlJpaRepository}. Represents the source-of-truth database plus (in the
 * full design) a Redis cache-aside layer in front of it (§4.1, §6.3); here H2 plays
 * both roles so the read/write shape is exercised without the extra infrastructure.
 */
@Repository
public class UrlRepository {

    private final UrlJpaRepository jpa;

    public UrlRepository(UrlJpaRepository jpa) {
        this.jpa = jpa;
    }

    /**
     * Inserts the record only if its code (the primary key) is not already taken —
     * the write-time uniqueness check that both generated codes and custom aliases
     * go through (NFR-1). The existence check and insert run in one transaction.
     *
     * @return {@code true} if stored, {@code false} if the code was already in use
     */
    @Transactional
    public boolean saveIfAbsent(UrlRecord record) {
        if (jpa.existsById(record.getCode())) {
            return false;
        }
        jpa.save(record);
        return true;
    }

    /** Persists a mutation (only {@code is_active} ever changes — delete/expiry). */
    public void save(UrlRecord record) {
        jpa.save(record);
    }

    public Optional<UrlRecord> findByCode(String code) {
        return jpa.findById(code);
    }

    public boolean exists(String code) {
        return jpa.existsById(code);
    }

    /** All rows; used by the reaper sweep (§6.6) and full-rebuild aggregation. */
    public List<UrlRecord> findAll() {
        return jpa.findAll();
    }

    public long count() {
        return jpa.count();
    }
}
