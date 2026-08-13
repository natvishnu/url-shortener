package com.example.urlshortener.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger metadata for the service. springdoc discovers the endpoints
 * from the {@code @RestController}s automatically; this bean only supplies the
 * top-level document info shown in the Swagger UI.
 *
 * <p>Once running, the interactive docs live at {@code /swagger-ui.html} and the
 * raw OpenAPI 3 spec at {@code /v3/api-docs}.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI urlShortenerOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("URL Shortener API")
                .description("Create, resolve, delete, and analyze short URLs.")
                .version("v1")
                .contact(new Contact().name("URL Shortener"))
                .license(new License().name("Apache 2.0")));
    }
}
