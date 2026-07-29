# URL Shortener

A simple Spring Boot service for creating, resolving, and deleting shortened URLs.

## What it does

This service exposes a small REST API that lets you:

- **Create** a shortened URL for a given long URL, optionally with a custom alias and/or a custom expiration time.
- **Redirect** (`302 Found`) from a short code to the original long URL.
- **Delete** (deactivate) a shortened URL so it can no longer be used for redirection.

URLs are currently stored in an in-memory map (`ConcurrentHashMap`) inside the controller. This is a temporary storage mechanism — all data is lost when the application restarts, and it is expected to be replaced by a persistent datastore in the future.

### Behavior details

- **Short code generation**: When no custom alias is given, a random 7-character Base62 code is generated. The service retries up to 10 times if a collision occurs, returning `500 Internal Server Error` if it cannot generate a unique code.
- **Custom aliases**: If `custom_alias` is provided, it must be 3–32 characters long and contain only letters, digits, `-`, or `_`. If the alias is already in use, the request fails with `409 Conflict`.
- **Long URL validation**: `long_url` must be a valid absolute `http://` or `https://` URL. Invalid or missing URLs return `400 Bad Request`.
- **Expiration**:
  - If `user_expires_at` is omitted, the URL defaults to expiring **30 days** after creation.
  - If provided, `user_expires_at` must be an ISO-8601 instant (e.g. `2025-01-01T00:00:00Z`) that is in the future; otherwise the request returns `400 Bad Request`.
  - Once a URL's expiration time has passed, redirect requests for it return `410 Gone`.
- **Deletion**: Deleting a URL marks it inactive (it is not removed from the store). Subsequent redirect attempts for a deactivated code return `410 Gone`. Deleting an unknown code returns `404 Not Found`.
- **Redirects for unknown codes** return `404 Not Found`.

## Building

This is a standard Maven project.

```bash
mvn clean install
```

## Running

```bash
mvn spring-boot:run
```

By default, the application starts on `http://localhost:8080`.

## API Examples

### Create a shortened URL

```bash
curl -i -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"long_url": "https://example.com/some/very/long/path"}'
```

Example response (`201 Created`):

```json
{
  "code": "aB3xY9z",
  "long_url": "https://example.com/some/very/long/path",
  "active": true,
  "created_at": "2024-05-01T12:00:00Z",
  "expires_at": "2024-05-31T12:00:00Z"
}
```

#### Create with a custom alias

```bash
curl -i -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"long_url": "https://example.com/some/path", "custom_alias": "my-link"}'
```

#### Create with a custom expiration

```bash
curl -i -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"long_url": "https://example.com/some/path", "user_expires_at": "2025-01-01T00:00:00Z"}'
```

### Follow a short URL (redirect)

```bash
curl -i http://localhost:8080/aB3xY9z
```

Example response:

```
HTTP/1.1 302 Found
Location: https://example.com/some/very/long/path
```

If the code doesn't exist, this returns `404 Not Found`. If it has been deleted or has expired, this returns `410 Gone`.

### Delete a shortened URL

```bash
curl -i -X DELETE http://localhost:8080/api/urls/aB3xY9z
```

Example response: `204 No Content`.

Deleting a code that doesn't exist returns `404 Not Found`.