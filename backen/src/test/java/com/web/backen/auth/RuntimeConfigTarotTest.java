package com.web.backen.auth;

import com.web.backen.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigTarotTest {
    @Test void independentDefaultOffSwitchSurvivesSettingsRoundTrip() {
        var db=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        try {
            var jdbc=new JdbcTemplate(db);jdbc.update("INSERT INTO app_settings(setting_key,setting_value) VALUES('matchmaking.relationship_enabled','true')");
            var runtime=new RuntimeConfigService(jdbc,new LlmConfig(),new BabelDocConfig(),new ZoteroConfig());
            assertFalse(runtime.tarotAiEnabled());runtime.update(Map.of("matchmaking",Map.of("tarotAiEnabled",true)));
            assertTrue(runtime.tarotAiEnabled());assertEquals(true,((Map<?,?>)runtime.publicSettings().get("matchmaking")).get("tarotAiEnabled"));
            runtime.update(Map.of("matchmaking",Map.of("model","test-model")));assertTrue(runtime.tarotAiEnabled());
            runtime.update(Map.of("matchmaking",Map.of("tarotAiEnabled",false)));assertFalse(runtime.tarotAiEnabled());
            assertEquals("true",jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key='matchmaking.relationship_enabled'",String.class));
        }finally{db.shutdown();}
    }
}
