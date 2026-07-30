# URL Shortener

A Spring Boot service for creating, resolving, and deleting shortened URLs, with
optional/defaulted expiry, custom aliases, usage-based rankings, and per-code
analytics.

This implementation follows `URL_Shortener_Requirements_and_Design.docx`. It is a
**prototype that demonstrates the design's approach at small scale** (§8 of the
design). The production architecture is Postgres (partitioned) + Redis + a
Snowflake-style ID service; here the `urls` and `clicks` tables live in a real SQL
engine (**H2 in-memory**, via Spring Data JPA) so uniqueness, transactions, and
soft-delete are enforced by the database, while the Redis sorted-set ranking layer
is stood in for by an in-memory component (`UsageMetrics`) — Redis is in-memory in
production too. Every component notes its production counterpart in its Javadoc.

## What it does

| Feature | Requirement | Notes |
| --- | --- | --- |
| Create | FR-1 | Shorten a long URL, with optional custom alias and optional expiry. |
| Delete | FR-2 | Soft delete (`is_active=false`); analytics history is preserved. |
| Redirect | FR-3 | `302 Found` to the long URL; `410 Gone` if expired/deleted; `404` if unknown. |
| Expiry | FR-4, NFR-6 | User-supplied or defaulted expiry, plus inactivity-based auto-expiry; a background reaper removes expired URLs. |
| Custom alias | FR-5 | Used verbatim as the code, subject to charset/length/reserved-word/uniqueness validation. |
| Rankings | FR-6, NFR-5 | Low-latency "most used / least used / least recently used" served from a maintained view. |
| Analytics | NFR-5 | Per-code click totals, last access, referrer breakdown, recent clicks. |

**Out of scope this iteration** (per the design): updating a short URL (targets are
immutable — delete and re-create), and spam/malicious-URL detection (only basic
input validation is performed).

## Design highlights

- **Uniqueness by construction (NFR-1).** Codes are base62-encoded, monotonically
  increasing Snowflake-style IDs (`timestamp | worker-id | sequence`), not a hash of
  the URL. Worker ids are partitioned per instance so codes never collide across
  instances. See `id/SnowflakeCodeGenerator`.
- **Insert-mostly `urls` row (NFR-2, NFR-4).** The stored record carries no
  `access_count`, `last_accessed_at`, or `updated_at` — the only post-creation write
  is `is_active` flipping to false on delete/expiry. The redirect path is a pure
  read. See `model/UrlRecord`.
- **Analytics off the hot path (§6.3, §6.5).** Every redirect appends one row to an
  append-only clicks stream **asynchronously** (`@Async`), and the usage rankings are
  maintained as two sorted-set-equivalent structures (by access count, by last
  access) on top of that stream. The redirect returns before the click write is
  durable. See `repository/ClickRepository` and `metrics/UsageMetrics`.
- **Expiry + reaper (FR-4, NFR-6, §6.6).** Expiry is enforced on every redirect
  regardless of reaper timing. A scheduled reaper deactivates URLs past their expiry
  or their inactivity window and evicts them from the cache and rankings, guarded by
  a safety cap that aborts a pass which would deactivate too large a fraction of
  active URLs. See `service/ReaperService`.
- **Self-healing rankings (§6.5).** A periodic job rebuilds the rankings from a full
  aggregation of the clicks stream, correcting any lost async update. See
  `service/MetricsRebuildJob`.
- **Availability over consistency (NFR-3, §6.4).** The redirect path is cache-aside
  with graceful DB fallback: a cache hit is a fast lookup, and if the cache is
  unavailable the request falls back to the database rather than failing — no single
  component is a hard dependency for a redirect. Click recording is failure-isolated
  so analytics errors never surface to the caller. See `cache/RedirectCache` and
  `service/UrlService`; the `redirectSurvivesCacheOutageViaDbFallback` test exercises
  the chaos scenario from §7.

## Configuration

All settings are system-wide (no per-URL overrides), configured in
`src/main/resources/application.properties`:

| Property | Default | Meaning |
| --- | --- | --- |
| `urlshortener.default-expiry-duration` | `P30D` | Expiry applied when the caller omits `user_expires_at` (FR-4). |
| `urlshortener.inactivity-window` | `P90D` | Un-accessed URLs are auto-expired after this (NFR-6). |
| `urlshortener.worker-id` | `1` | Snowflake worker id; must be distinct per instance. |
| `urlshortener.reserved-aliases` | `api,admin,top,analytics,health,actuator` | Codes a custom alias may not take. |
| `urlshortener.reaper.interval-millis` | `60000` | How often the reaper sweeps. |
| `urlshortener.reaper.safety-cap-percent` | `20` | Max % of active URLs a single reaper pass may deactivate. |

## Building and running

Standard Maven project (Java 21):

```bash
mvn clean install      # build + run tests
mvn spring-boot:run    # start on http://localhost:8080
```

## API

### Create — `POST /api/urls`

```bash
curl -i -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"long_url": "https://example.com/some/very/long/path"}'
```

Body fields: `long_url` (required), `custom_alias` (optional), `user_expires_at`
(optional ISO-8601 instant, must be in the future). Example `201 Created` response:

```json
{
  "code": "3fB9zk2",
  "long_url": "https://example.com/some/very/long/path",
  "active": true,
  "created_at": "2026-07-29T12:00:00Z",
  "expires_at": "2026-08-28T12:00:00Z"
}
```

- `400 Bad Request` — missing/invalid `long_url`, malformed `user_expires_at`, past
  expiry, or an alias that violates the charset/length rules.
- `409 Conflict` — the custom alias is already taken or is reserved.

### Redirect — `GET /{code}`

```bash
curl -i http://localhost:8080/3fB9zk2
```

`302 Found` with a `Location` header; `404` if unknown; `410 Gone` if deleted or
expired. Each redirect asynchronously records a click.

### Delete — `DELETE /api/urls/{code}`

```bash
curl -i -X DELETE http://localhost:8080/api/urls/3fB9zk2
```

`204 No Content` on success (soft delete); `404` if the code is unknown.

### Rankings — `GET /api/urls/top?by=most_used|least_used|lru&limit=N`

```bash
curl -s "http://localhost:8080/api/urls/top?by=most_used&limit=10"
```

`by` accepts `most_used` (a.k.a. `frequently_used`), `least_used`, or `lru`
(a.k.a. `least_recently_used`). Returns entries with `code`, `access_count`, and
`last_accessed_at`.

### Analytics — `GET /api/urls/{code}/analytics`

```bash
curl -s http://localhost:8080/api/urls/3fB9zk2/analytics
```

Returns the code's `active`/`created_at`/`expires_at`, `total_clicks`,
`last_accessed_at`, a `referrers` breakdown, and the most recent click timestamps.

## Note on persistence

The `urls` and `clicks` tables are persisted to an **H2 in-memory database** through
Spring Data JPA; schema is auto-created on startup (`ddl-auto=create-drop`) and all
data resets when the app stops. A browsable console is available in development at
`http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:urlshortener`, user `sa`,
empty password).

Mapping to production per the design: `UrlRepository` → partitioned Postgres + Redis
cache-aside; `ClickRepository` → time-partitioned append-only clicks table;
`UsageMetrics` → Redis sorted sets; `SnowflakeCodeGenerator` → a distributed ID
service. Swapping H2 for Postgres is a dependency + datasource-URL change; the JPA
entities and repositories are unchanged.
