package com.web.backen.auth;

import com.web.backen.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServicePauseFilterTest {
    @Test
    void mapsOnlyPausableModulePrefixes() {
        assertEquals("Contact", ServicePauseFilter.featureFor("/api/ppt-generate/tasks"));
        assertEquals("ImageGenerate", ServicePauseFilter.featureFor("/api/image-generate"));
        assertEquals("Publications", ServicePauseFilter.featureFor("/api/zotero/items"));
        assertNull(ServicePauseFilter.featureFor("/api/translate/tasks"));
        assertNull(ServicePauseFilter.featureFor("/api/zotero-other"));
    }

    @Test
    void pausedModulesReturn503ForEveryoneWhileOthersPassThrough() throws Exception {
        var db = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        try {
            var jdbc = new JdbcTemplate(db);
            jdbc.update("INSERT INTO app_settings(setting_key,setting_value) VALUES("
                    + "'service.paused_features',' Contact,ImageGenerate , Publications,Unknown')");
            var runtime = new RuntimeConfigService(jdbc, new LlmConfig(), new BabelDocConfig(), new ZoteroConfig());
            assertEquals(List.of("Contact", "ImageGenerate", "Publications"), runtime.pausedFeatures());
            assertEquals(runtime.pausedFeatures(), runtime.publicSettings().get("pausedFeatures"));
            var filter = new ServicePauseFilter(runtime);

            var paused = new MockHttpServletResponse();
            var pausedChain = new MockFilterChain();
            filter.doFilter(new MockHttpServletRequest("POST", "/api/ppt-generate/tasks"), paused, pausedChain);
            assertEquals(503, paused.getStatus());
            assertNull(pausedChain.getRequest());
            assertTrue(paused.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("PPT 生成暂停服务"));

            var open = new MockHttpServletResponse();
            var openChain = new MockFilterChain();
            filter.doFilter(new MockHttpServletRequest("POST", "/api/translate/tasks"), open, openChain);
            assertEquals(200, open.getStatus());
            assertNotNull(openChain.getRequest());

            jdbc.update("UPDATE app_settings SET setting_value='' WHERE setting_key='service.paused_features'");
            assertTrue(runtime.pausedFeatures().isEmpty());
            var resumedChain = new MockFilterChain();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/zotero/items"), new MockHttpServletResponse(), resumedChain);
            assertNotNull(resumedChain.getRequest());
        } finally {
            db.shutdown();
        }
    }
}
