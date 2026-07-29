# URL Shortener

A simple URL shortener service built with Spring Boot. It allows clients to submit a long URL and receive a short code in return, redirect from the short code to the original URL, and delete a shortened URL when it's no longer needed.

## Features

- Create a short URL from a long URL
- Redirect requests using the generated short code to the original URL
- Delete an existing short URL by its code

## Prerequisites

- Java 17+ (or the JDK version configured in the project's `pom.xml`)
- Maven 3.6+

## Build

Build the project and run the test suite with:

```bash
mvn clean install
```

## Run

Start the application with:

```bash
mvn spring-boot:run
```

By default, the application starts on `http://localhost:8080`.

## API Usage

### Create a Short URL

Send a `POST` request with the original URL to create a shortened version:

```bash
curl -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"url": "https://www.example.com/some/very/long/path"}'
```

Example response:

```json
{
  "code": "abc123",
  "shortUrl": "http://localhost:8080/abc123",
  "originalUrl": "https://www.example.com/some/very/long/path"
}
```

### Redirect to the Original URL

Visiting the short URL redirects to the original URL:

```bash
curl -i http://localhost:8080/abc123
```

Example response:

```
HTTP/1.1 302 Found
Location: https://www.example.com/some/very/long/path
```

### Delete a Short URL

Remove a shortened URL using its code:

```bash
curl -X DELETE http://localhost:8080/api/urls/abc123
```

A successful deletion returns a `204 No Content` response. Attempting to redirect to a deleted short code afterward will result in a `404 Not Found`.