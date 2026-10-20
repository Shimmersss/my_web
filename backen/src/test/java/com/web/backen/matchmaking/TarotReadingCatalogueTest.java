package com.web.backen.matchmaking;

import com.web.backen.auth.AuthException;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TarotReadingCatalogueTest {
    @Test void frontendAndServerAreByteIdentical() throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();if(!Files.exists(root.resolve("front")))root=root.getParent();
        assertArrayEquals(Files.readAllBytes(root.resolve("front/src/data/tarotReading.json")),getClass().getResourceAsStream("/tarot-reading-v1.json").readAllBytes());TarotReadingCatalogue.initialize();
    }
    @Test void romanceIsAValidDirectionForEveryExistingSpread() {
        assertEquals("恋爱关系",TarotReadingCatalogue.topic("romance").get("label"));
        for(String spread:List.of("single","triangle","choice")) {
            var body=new HashMap<>(TarotTestSupport.form(spread));body.put("topicId","romance");body.put("question","我可以怎样更清楚地表达恋爱关系中的需要？");
            assertEquals("romance",TarotReadingCatalogue.context(body).get("topicId"));
        }
    }
    @Test void formConstraintsAndIrrelevantOptions() {
        assertEquals(3,TarotReadingCatalogue.positions("triangle").size());
        for(String spread:List.of("single","triangle","choice")) assertEquals(spread,TarotReadingCatalogue.context(TarotTestSupport.form(spread)).get("spreadId"));
        var body=new HashMap<>(TarotTestSupport.form("choice"));body.put("optionB","留下");assertThrows(AuthException.class,()->TarotReadingCatalogue.context(body));
        body.put("optionB","🌙".repeat(25));assertThrows(AuthException.class,()->TarotReadingCatalogue.context(body));
        body.put("spreadId","single");assertNull(TarotReadingCatalogue.context(body).get("options"));
        body.put("question","🌙".repeat(120));assertNotNull(TarotReadingCatalogue.context(body));body.put("question","🌙".repeat(121));assertThrows(AuthException.class,()->TarotReadingCatalogue.context(body));
    }
}
