package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MatchmakingTarotServiceTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private MatchmakingTarotService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        jdbc.update("INSERT INTO users(id,username,password_hash,role,credits) VALUES(1,'alice','x','USER',0)");
        transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        service = new MatchmakingTarotService(jdbc, new ObjectMapper(), transactions);
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    @Test
    void drawIsReusedForTwentyFourHoursAndContainsUniqueUprightCards() {
        Map<String, Object> first = service.draw(1);
        Map<String, Object> second = service.draw(1);
        assertEquals(first.get("drawId"), second.get("drawId"));
        List<Map<String, Object>> cards = (List<Map<String, Object>>) first.get("cards");
        assertEquals(3, cards.size());
        assertEquals(3, new HashSet<>(cards.stream().map(card -> card.get("cardId")).toList()).size());
        assertTrue(cards.stream().allMatch(card -> "upright".equals(card.get("orientation"))));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_tarot_draws", Integer.class));
    }

    @Test
    void consumedDrawCannotBeUsedTwiceButIsRestoredAfterFailure() {
        Map<String, Object> draw = service.draw(1);
        String drawId = String.valueOf(draw.get("drawId"));
        Map<String, Object> consumed = transactions.execute(status -> service.consume(1, drawId, "task-00000001"));
        assertEquals(drawId, consumed.get("drawId"));
        assertThrows(AuthException.class, () -> service.draw(1));
        assertThrows(AuthException.class, () -> transactions.execute(status -> service.consume(1, drawId, "task-00000002")));

        transactions.executeWithoutResult(status -> service.restoreAfterFailure(drawId, "task-00000001"));
        Map<String, Object> restored = service.current(1).get("draw") instanceof Map<?, ?> value
                ? (Map<String, Object>) value : Map.of();
        assertEquals(drawId, restored.get("drawId"));
        assertEquals(drawId, transactions.execute(status -> service.consume(1, drawId, "task-00000002")).get("drawId"));
    }

    @Test
    void limitsNewDrawCreationWithinOneHour() {
        for (int i = 0; i < 12; i++) {
            Map<String, Object> draw = service.draw(1);
            String drawId = String.valueOf(draw.get("drawId"));
            String taskId = String.format("task-%08d", i);
            transactions.execute(status -> service.consume(1, drawId, taskId));
            service.markReport(drawId, taskId, "report-" + i);
        }
        assertThrows(AuthException.class, () -> service.draw(1));
    }
}
