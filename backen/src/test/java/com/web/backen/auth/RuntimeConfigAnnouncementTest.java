package com.web.backen.auth;

import com.web.backen.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigAnnouncementTest {
    @Test void announcementDefaultsOffAndRoundTripsAsSingleLinePlainText() {
        var db=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        try {
            var runtime=new RuntimeConfigService(new JdbcTemplate(db),new LlmConfig(),new BabelDocConfig(),new ZoteroConfig());
            assertEquals(Map.of("enabled",false,"title","公告","content","","tone","info"),runtime.announcement());
            runtime.update(Map.of("announcement",Map.of("enabled",true,"title"," 维护 ","content","今晚\n22:00\t停机","tone","WARNING")));
            assertEquals(Map.of("enabled",true,"title","维护","content","今晚 22:00 停机","tone","warning"),runtime.publicSettings().get("announcement"));
            runtime.update(Map.of("visibility",Map.of("News","USER")));
            assertEquals(true,runtime.announcement().get("enabled"));
            runtime.update(Map.of("announcement",Map.of("enabled",false,"title","","content","")));
            assertEquals(Map.of("enabled",false,"title","公告","content","","tone","info"),runtime.announcement());
        }finally{db.shutdown();}
    }

    @Test void announcementRejectsOverlongOrEmptyEnabledContent() {
        var db=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        try {
            var runtime=new RuntimeConfigService(new JdbcTemplate(db),new LlmConfig(),new BabelDocConfig(),new ZoteroConfig());
            assertThrows(AuthException.class,()->runtime.update(Map.of("announcement",Map.of("enabled",true,"content"," "))));
            assertThrows(AuthException.class,()->runtime.update(Map.of("announcement",Map.of("enabled",false,"content","字".repeat(201)))));
            assertThrows(AuthException.class,()->runtime.update(Map.of("announcement",Map.of("enabled",false,"title","字".repeat(21)))));
            assertThrows(AuthException.class,()->runtime.update(Map.of("announcement",Map.of("enabled",false,"tone","danger"))));
            assertEquals(false,runtime.announcement().get("enabled"));
        }finally{db.shutdown();}
    }
}
