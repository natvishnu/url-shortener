package com.example.urlshortener.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the URL shortener REST controller.
 *
 * Assumed API contract:
 *   POST   /api/urls      { "url": "<long url>" }  -> 201 Created, body contains "code" and/or "shortUrl"
 *   GET    /{code}                                  -> 302/301 redirect with Location header set to the long URL
 *   DELETE /api/urls/{code}                          -> 204 No Content
 *   GET    /{code} (after delete)                    -> 410 Gone
 *   GET    /{unknownCode}                            -> 404 Not Found
 */
@SpringBootTest
@AutoConfigureMockMvc
class UrlShortenerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String LONG_URL = "https://www.example.com/some/very/long/path?query=value";

    private String createShortUrl(String longUrl) throws Exception {
        String requestBody = objectMapper.writeValueAsString(java.util.Map.of("url", longUrl));

        MvcResult result = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(responseBody);

        assertThat(json.has("code")).isTrue();
        String code = json.get("code").asText();
        assertThat(code).isNotBlank();
        return code;
    }

    @Test
    void createReturnsShortCode() throws Exception {
        String requestBody = objectMapper.writeValueAsString(java.util.Map.of("url", LONG_URL));

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.code").value(matchesPattern("^[A-Za-z0-9_-]+$")));
    }

    @Test
    void redirectFollowsToLongUrl() throws Exception {
        String code = createShortUrl(LONG_URL);

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", LONG_URL));
    }

    @Test
    void deleteThenRedirectReturns410() throws Exception {
        String code = createShortUrl(LONG_URL);

        // sanity check: redirect works before delete
        mockMvc.perform(get("/{code}", code))
                .andExpect(status().is3xxRedirection());

        // delete the short url
        mockMvc.perform(delete("/api/urls/{code}", code))
                .andExpect(status().isNoContent());

        // subsequent access should be gone
        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isGone());
    }

    @Test
    void unknownCodeReturns404() throws Exception {
        String unknownCode = "doesNotExist123";

        mockMvc.perform(get("/{code}", unknownCode))
                .andExpect(status().isNotFound());
    }
}