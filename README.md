# URL Shortener

A simple Spring Boot application that shortens long URLs, redirects short codes to their original destination, and allows short URLs to be deleted.

## Features

- Create a shortened URL from a long URL
- Redirect from a short code to the original long URL
- Delete an existing short URL

## Requirements

- Java 17 (or the JDK version configured in `pom.xml`)
- Maven 3.6+

## Building the Project

Build the application and run the test suite with Maven:

```bash
mvn clean install
```

This compiles the source, runs the tests, and packages the application as a runnable JAR.

## Running the Application

Start the application locally with the Spring Boot Maven plugin:

```bash
mvn spring-boot:run
```

By default the application starts on `http://localhost:8080`.

## API Usage

### Create a Short URL

Send a POST request with the original URL to shorten:

```bash
curl -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"url": "https://www.example.com/some/very/long/path"}'
```

Example response:

```json
{
  "shortCode": "abc123",
  "shortUrl": "http://localhost:8080/abc123",
  "originalUrl": "https://www.example.com/some/very/long/path"
}
```

### Redirect to the Original URL

Access the generated short code to be redirected to the original URL:

```bash
curl -L http://localhost:8080/abc123
```

The `-L` flag tells curl to follow the redirect. Without `-L`, curl will show the HTTP redirect response (e.g., a `302` status with a `Location` header pointing to the original URL).

### Delete a Short URL

Remove a short URL by its short code:

```bash
curl -X DELETE http://localhost:8080/api/urls/abc123
```

A successful deletion returns an HTTP `204 No Content` response. Requesting the deleted short code afterward will no longer redirect.