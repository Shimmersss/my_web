package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MatchmakingRelationshipTaskTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private MatchmakingService service;
    private QuotaService quota;
    private TransactionTemplate transactions;
    private MatchmakingTarotService tarot;
    private final AuthUser user = new AuthUser(1, "alice", "USER", 20, true);

    @BeforeEach
    void setUp() throws Exception {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        jdbc.update("INSERT INTO users(id,username,password_hash,role,credits) VALUES(1,'alice','x','USER',20)");
        quota = new QuotaService(jdbc); quota.initializeDefaults();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        tarot = new MatchmakingTarotService(jdbc, new ObjectMapper(), transactions);
        service = new MatchmakingService(jdbc, new ObjectMapper(), input -> narrative(), quota, null, transactions,
                null, null, tarot, Files.createTempDirectory("matchmaking-relationship-task").toString());
        service.init();
    }

    @AfterEach
    void tearDown() { service.shutdown(); database.shutdown(); }

    @Test
    void relationshipTaskConsumesOneDrawAndPersistsVersionedReportAtomically() throws Exception {
        Map<String, Object> draw = tarot.draw(1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reportVersion", MatchmakingService.RELATIONSHIP_REPORT_VERSION);
        body.put("questionnaireVersion", RelationshipQuestionnaire.QUESTIONNAIRE_VERSION);
        body.put("relationshipStage", "gettingCloser"); body.put("explorationIntent", "communicateBetter");
        body.put("personalityMode", "skip"); body.put("personalityAnswers", Map.of());
        body.put("relationshipAnswers", Map.of("r01", -2, "r02", 1, "r03", 0, "r04", 2, "r05", -1));
        body.put("personalNote", "我想把在意的事说清楚"); body.put("drawId", draw.get("drawId"));
        body.put("lifeContext", Map.of("city", "杭州", "workRhythm", "周末较松"));

        String taskId = String.valueOf(service.createTask(user, body).get("taskId"));
        Map<String, Object> task = awaitTerminal(taskId);
        assertEquals("done", task.get("status"));
        assertEquals(MatchmakingService.RELATIONSHIP_REPORT_VERSION, task.get("reportVersion"));
        assertEquals(18, quota.balance(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM credit_transactions WHERE kind='SPEND'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_reports", Integer.class));

        String reportId = String.valueOf(task.get("reportId"));
        Map<String, Object> report = service.report(user, reportId);
        assertEquals(MatchmakingService.RELATIONSHIP_REPORT_VERSION, report.get("reportVersion"));
        assertEquals("major-22-v1", report.get("deckVersion"));
        assertEquals("skip", ((Map<?, ?>) report.get("personality")).get("source"));
        assertEquals(3, ((Map<?, ?>) report.get("tarot")).get("cards") instanceof List<?> cards ? cards.size() : 0);
        assertEquals(5, ((List<?>) report.get("relationshipPreferences")).size());
        assertEquals(reportId, jdbc.queryForObject("SELECT consumed_report_id FROM matchmaking_tarot_draws WHERE id=?", String.class, draw.get("drawId")));
    }

    private Map<String, Object> awaitTerminal(String taskId) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            Map<String, Object> task = service.task(user, taskId);
            if (Set.of("done", "error").contains(task.get("status")) && !Boolean.TRUE.equals(task.get("compensationPending"))) return task;
            Thread.sleep(10);
        }
        fail("relationship task did not settle");
        return Map.of();
    }

    private static Map<String, Object> narrative() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("identity", Map.of("title", "坦率的探索者", "headline", "把想说的话放到关系里，也把回应留给对方。", "introduction", "这是一张练习观察与表达的地图，不替你预言关系会走向哪里。"));
        result.put("tarotReadings", List.of(Map.of("slot", "present", "cardId", "major-00", "interpretation", "先从好奇开始。", "evidenceIds", List.of("r01"))));
        result.put("relationshipManual", List.of()); result.put("recurringPatterns", List.of());
        result.put("attraction", Map.of("spark", "好奇", "sustainable", "回应", "friction", "猜测"));
        result.put("nextSteps", Map.of("scripts", List.of(), "experiment", "今天完成一次具体表达。"));
        return result;
    }
}
