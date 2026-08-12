package com.web.backen.auth;

import com.web.backen.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

class RuntimeConfigImageTest {
    @Test
    void derivesGenerationAndEditEndpointsFromACompleteGenerationUrl() {
        JdbcTemplate jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build());
        PptGenerationConfig ppt = new PptGenerationConfig();
        ppt.setImageGenerationEndpoint("https://relay.example/v1/images/generations");
        RuntimeConfigService runtime = new RuntimeConfigService(jdbc, new LlmConfig(), new BabelDocConfig(),
                new ZoteroConfig(), ppt, new TranslationConfig(), new ImageGenerationConfig());

        assertEquals("https://relay.example/v1/images/generations", runtime.imageGenerationEndpoint());
        assertEquals("https://relay.example/v1/images/edits", runtime.imageEditEndpoint());
    }

    @Test
    void derivesEndpointsFromAnEditsUrlAndKeepsImageFeatureLoginOnlyByDefault() {
        JdbcTemplate jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build());
        jdbc.update("INSERT INTO app_settings(setting_key, setting_value) VALUES ('ppt.image-generation.url', 'https://relay.example/v1/images/edits')");
        RuntimeConfigService runtime = new RuntimeConfigService(jdbc, new LlmConfig(), new BabelDocConfig(),
                new ZoteroConfig(), new PptGenerationConfig(), new TranslationConfig(), new ImageGenerationConfig());

        assertEquals("https://relay.example/v1/images/generations", runtime.imageGenerationEndpoint());
        assertEquals("https://relay.example/v1/images/edits", runtime.imageEditEndpoint());
        assertEquals("USER", runtime.visibilityLevel("ImageGenerate"));
    }

    @Test
    void keepsGlobalRetentionIndependentFromPerUserRetention() {
        JdbcTemplate jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build());
        RuntimeConfigService runtime = new RuntimeConfigService(jdbc, new LlmConfig(), new BabelDocConfig(),
                new ZoteroConfig(), new PptGenerationConfig(), new TranslationConfig(), new ImageGenerationConfig());

        runtime.update(Map.of(
                "pptRetention", Map.of("maxPerUser", 5, "maxTotal", 1),
                "translationRetention", Map.of("maxPerUser", 5, "maxTotal", 2),
                "imageRetention", Map.of("maxPerUser", 5, "maxTotal", 3)));

        assertEquals(1, runtime.pptMaxGlobalHistory());
        assertEquals(2, runtime.translationMaxGlobalHistory());
        assertEquals(3, runtime.imageMaxGlobalHistory());
    }
}
