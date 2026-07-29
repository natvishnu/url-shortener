package com.example.urlshortener.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a single shortened URL row.
 *
 * <p>This class is insert-mostly: once created, every field is immutable
 * except for {@link #isActive}