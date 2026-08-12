package com.web.backen.auth;

import com.web.backen.config.BabelDocConfig;
import com.web.backen.config.ImageGenerationConfig;
import com.web.backen.config.LlmConfig;
import com.web.backen.config.PptGenerationConfig;
import com.web.backen.config.TranslationConfig;
import com.web.backen.config.ZoteroConfig;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.net.http.HttpRequest;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigBraveImagesTest {
    @Test
    void savesMaskedKeyAndClampsSearchLimits() {
        JdbcTemplate jdbc = database();
        RuntimeConfigService runtime = runtime(jdbc);

        runtime.update(Map.of("braveImages", Map.of(
                "apiKey", "brave-secret-1234",
                "count", 500,
                "maxQueries", 99)));

        assertEquals("https://api.search.brave.com/res/v1/images/search", runtime.braveImagesEndpoint());
        assertEquals("brave-secret-1234", runtime.braveImagesKey());
        assertEquals(50, runtime.braveImagesCount());
        assertEquals(3, runtime.braveImagesMaxQueries());
        @SuppressWarnings("unchecked")
        Map<String, Object> publicConfig = (Map<String, Object>) runtime.publicSettings().get("braveImages");
        assertEquals("strict", publicConfig.get("safeSearch"));
        assertEquals("database", publicConfig.get("configSource"));
        assertEquals("已配置（1234）", publicConfig.get("apiKeyHint"));
        assertFalse(publicConfig.containsValue("brave-secret-1234"));
    }

    @Test
    void blankSubmittedKeyKeepsExistingSecret() {
        JdbcTemplate jdbc = database();
        RuntimeConfigService runtime = runtime(jdbc);
        runtime.update(Map.of("braveImages", Map.of("apiKey", "brave-secret-5678", "count", 20, "maxQueries", 2)));

        runtime.update(Map.of("braveImages", Map.of("apiKey", "", "count", -1, "maxQueries", -1)));

        assertEquals("brave-secret-5678", runtime.braveImagesKey());
        assertEquals(1, runtime.braveImagesCount());
        assertEquals(1, runtime.braveImagesMaxQueries());
    }

    @Test
    void latencyProbeUsesFixedSafeRequestAndParsesResultCount() {
        JdbcTemplate jdbc = database();
        RecordingRuntimeConfig runtime = new RecordingRuntimeConfig(jdbc, 200,
                "{\"query\":{\"altered\":\"OpenAI brand logo\"},\"results\":[{\"title\":\"OpenAI\"}]}");

        Map<String, Object> result = runtime.testBraveImagesConnection("probe-secret");

        assertEquals(1, result.get("resultCount"));
        assertEquals("OpenAI brand logo", result.get("alteredQuery"));
        assertTrue(runtime.request.uri().toString().startsWith(
                "https://api.search.brave.com/res/v1/images/search?q=OpenAI+logo&count=1&safesearch=strict"));
        assertEquals("probe-secret", runtime.request.headers().firstValue("X-Subscription-Token").orElseThrow());
        assertEquals("GET", runtime.request.method());
    }

    @Test
    void latencyProbeMapsProviderFailuresWithoutReturningBodyOrKey() {
        JdbcTemplate jdbc = database();
        for (Map.Entry<Integer, String> expected : Map.of(
                401, "认证失败", 403, "被拒绝", 422, "未通过校验", 429, "请求过于频繁").entrySet()) {
            RuntimeConfigService runtime = new RecordingRuntimeConfig(jdbc, expected.getKey(),
                    "provider-debug probe-secret trace");
            AuthException error = assertThrows(AuthException.class,
                    () -> runtime.testBraveImagesConnection("probe-secret"));
            assertEquals(expected.getKey(), error.getStatus());
            assertTrue(error.getMessage().contains(expected.getValue()));
            assertFalse(error.getMessage().contains("probe-secret"));
            assertFalse(error.getMessage().contains("provider-debug"));
        }
    }

    private JdbcTemplate database() {
        return new JdbcTemplate(new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build());
    }

    private RuntimeConfigService runtime(JdbcTemplate jdbc) {
        return new RuntimeConfigService(jdbc, new LlmConfig(), new BabelDocConfig(), new ZoteroConfig(),
                new PptGenerationConfig(), new TranslationConfig(), new ImageGenerationConfig());
    }

    private static final class RecordingRuntimeConfig extends RuntimeConfigService {
        private final int status;
        private final String body;
        private HttpRequest request;

        private RecordingRuntimeConfig(JdbcTemplate jdbc, int status, String body) {
            super(jdbc, new LlmConfig(), new BabelDocConfig(), new ZoteroConfig(),
                    new PptGenerationConfig(), new TranslationConfig(), new ImageGenerationConfig());
            this.status = status;
            this.body = body;
        }

        @Override
        protected BraveImagesResponse sendBraveImagesRequest(HttpRequest request) {
            this.request = request;
            return new BraveImagesResponse(status, body);
        }
    }
}
