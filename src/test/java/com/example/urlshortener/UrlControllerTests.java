package com.example.urlshortener.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link UrlController}, exercised via a standalone MockMvc
 * setup (no Spring context needed since the controller has no external
 * dependencies beyond its in-memory store).
 */
class UrlControllerTest {

    private static final String LONG_URL = "https://example.com/some/very/long/path";

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Fresh controller instance per test to isolate the in-memory store.
        mockMvc = MockMvcBuilders.standaloneSetup(new UrlController()).build();
    }

    @Test
    void createUrlReturnsGeneratedCode() throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                new UrlController.CreateUrlRequest(LONG_URL, null, null));

        MvcResult result = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.long_url").value(LONG_URL))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String code = json.get("code").asText();
        assertNotNull(code);
        assertTrue(code.length() > 0);
    }

    @Test
    void redirectFollowsToLongUrl() throws Exception {
        String code = createShortUrl(LONG_URL);

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isFound())
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
    void redirectForUnknownCodeReturnsNotFound() throws Exception {
        mockMvc.perform(get("/{code}", "doesNotExist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteForUnknownCodeReturnsNotFound() throws Exception {
        mockMvc.perform(delete("/api/urls/{code}", "doesNotExist"))
                .andExpect(status().isNotFound());
    }

    private String createShortUrl(String longUrl) throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                new UrlController.CreateUrlRequest(longUrl, null, null));

        MvcResult result = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("code").asText();
    }
}