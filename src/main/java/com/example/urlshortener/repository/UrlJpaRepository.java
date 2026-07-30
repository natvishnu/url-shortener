package com.example.urlshortener.repository;

import com.example.urlshortener.model.UrlRecord;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@code urls} table, keyed by short code.
 * In production this is the partitioned Postgres of §6.7; here it runs against H2.
 */
public interface UrlJpaRepository extends JpaRepository<UrlRecord, String> {
}
