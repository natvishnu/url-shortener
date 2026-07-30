package com.example.urlshortener;

import com.example.urlshortener.cache.RedirectCache;
import com.example.urlshortener.metrics.UsageMetrics;
import com.example.urlshortener.repository.ClickJpaRepository;
import com.example.urlshortener.repository.UrlJpaRepository;
import com.example.urlshortener.web.dto.CreateUrlRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the REST surface, wired against the full Spring context and the H2
 * database. Click recording is normally {@code @Async}; the {@link SyncAsyncConfig}
 * below swaps in a synchronous executor so a redirect's click is recorded inline,
 * which makes the ranking and analytics assertions deterministic.
 */
@SpringBootTest
class UrlControllerTest {

    private static final String LONG_URL = "https://example.com/some/very/long/path";

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private UrlJpaRepository urlJpaRepository;
    @Autowired
    private ClickJpaRepository clickJpaRepository;
    @Autowired
    private UsageMetrics usageMetrics;
    @Autowired
    private RedirectCache redirectCache;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Forces {@code @Async} click recording to run synchronously during tests. */
    @TestConfiguration
    static class SyncAsyncConfig implements AsyncConfigurer {
        @Override
        @Bean
        public Executor getAsyncExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @BeforeEach
    void setUp() {
        // Reset all persistent and in-memory state so tests are independent.
        clickJpaRepository.deleteAll();
        urlJpaRepository.deleteAll();
        usageMetrics.clear();
        redirectCache.clear();
        redirectCache.restore();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void createUrlReturnsGeneratedCode() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LONG_URL, null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.long_url").value(LONG_URL))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.expires_at").isNotEmpty())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertNotNull(json.get("code").asText());
        assertTrue(json.get("code").asText().length() > 0);
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

    @Test
    void customAliasIsHonoredVerbatim() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LONG_URL, "my-custom-alias", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("my-custom-alias"));

        mockMvc.perform(get("/{code}", "my-custom-alias"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LONG_URL));
    }

    @Test
    void conflictingAliasIsRejected() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LONG_URL, "taken-alias", null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LONG_URL, "taken-alias", null)))
                .andExpect(status().isConflict());
    }

    @Test
    void reservedAliasIsRejected() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LONG_URL, "admin", null)))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidAliasIsRejected() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LONG_URL, "no spaces!", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidLongUrlIsRejected() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("not-a-url", null, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pastExpiryIsRejected() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LONG_URL, null, "2000-01-01T00:00:00Z")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void topMostUsedReflectsRedirectBurst() throws Exception {
        String hot = createShortUrl("https://example.com/hot");
        String cold = createShortUrl("https://example.com/cold");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/{code}", hot)).andExpect(status().isFound());
        }
        mockMvc.perform(get("/{code}", cold)).andExpect(status().isFound());

        MvcResult result = mockMvc.perform(get("/api/urls/top").param("by", "most_used").param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(hot, json.get(0).get("code").asText());
        assertEquals(3, json.get(0).get("access_count").asLong());
        assertEquals(cold, json.get(1).get("code").asText());
    }

    @Test
    void analyticsReflectsClicks() throws Exception {
        String code = createShortUrl(LONG_URL);
        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());
        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());

        mockMvc.perform(get("/api/urls/{code}/analytics", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.total_clicks").value(2))
                .andExpect(jsonPath("$.last_accessed_at").isNotEmpty());
    }

    @Test
    void topRejectsUnknownRanking() throws Exception {
        mockMvc.perform(get("/api/urls/top").param("by", "bogus"))
                .andExpect(status().isBadRequest());
    }

    /**
     * NFR-3 chaos scenario (§6.4, §7): with the redirect cache "down", a redirect must
     * still succeed by falling back to the database — availability over consistency.
     */
    @Test
    void redirectSurvivesCacheOutageViaDbFallback() throws Exception {
        String code = createShortUrl(LONG_URL);

        redirectCache.simulateOutage();
        assertFalse(redirectCache.isAvailable());

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LONG_URL));
    }

    private String createShortUrl(String longUrl) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(longUrl, null, null)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText();
    }

    private String body(String longUrl, String alias, String expiresAt) throws Exception {
        return objectMapper.writeValueAsString(new CreateUrlRequest(longUrl, alias, expiresAt));
    }
}
