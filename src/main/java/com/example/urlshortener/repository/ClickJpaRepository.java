package com.example.urlshortener.repository;

import com.example.urlshortener.model.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for the append-only {@code clicks} table (§4.2).
 */
public interface ClickJpaRepository extends JpaRepository<ClickEvent, Long> {

    /** All clicks for a code, backing the per-code analytics query (NFR-5). */
    List<ClickEvent> findByCode(String code);
}
