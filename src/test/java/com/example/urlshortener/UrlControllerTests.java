package com.example.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the URL shortener controller.
 *
 * Assumed API contract:
 *   POST   /api/urls          { "url": "<long url>" }  -> 201 Created, body { "code": "<code>", "url": "<long url>" }
 *   GET    /{code}                                       -> 302 Found, Location header = original long url
 *   DELETE /api/urls/{code}                              -> 204 No Content
 *   GET    /{code} (unknown code)                        -> 404 Not Found
 *   GET    /{code} (deleted code)                        -> 410 Gone
 */
@SpringBootTest
@AutoConfigureMockMvc
class UrlShortenerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String LONG_URL = "https://www.example.com/some/very/long/path?query=param";

    private String createShortUrl(String longUrl) throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of("url", longUrl));

        MvcResult result = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").exists())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<?, ?> response = objectMapper.readValue(responseBody, Map.class);
        String code = (String) response.get("code");
        assertNotNull(code, "Created response should contain a non-null short code");
        assertTrue(code.length() > 0, "Short code should not be empty");
        return code;
    }

    @Test
    void createReturnsAShortCode() throws Exception {
        String code = createShortUrl(LONG_URL);
        assertNotNull(code);
    }

    @Test
    void redirectFollowsToTheLongUrl() throws Exception {
        String code = createShortUrl(LONG_URL);

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", LONG_URL));
    }

    @Test
    void deleteThenRedirectReturnsGone() throws Exception {
        String code = createShortUrl(LONG_URL);

        mockMvc.perform(delete("/api/urls/{code}", code))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isGone());
    }

    @Test
    void unknownCodeReturnsNotFound() throws Exception {
        String unknownCode = "doesNotExist123";

        mockMvc.perform(get("/{code}", unknownCode))
                .andExpect(status().isNotFound());
    }
}