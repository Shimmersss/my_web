package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthUser;
import com.web.backen.settings.RuntimeConfigService;
import com.web.backen.config.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MatchmakingReportAccessTest {
    private JdbcTemplate jdbc;
    private RuntimeConfigService runtime;
    private MatchmakingService service;
    private AuthUser alice;
    private AuthUser bob;
    private AuthUser root;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build());
        runtime = new RuntimeConfigService(jdbc, new LlmConfig(), new BabelDocConfig(),
                new ZoteroConfig(), new PptGenerationConfig(), new TranslationConfig(), new ImageGenerationConfig());
        service = new MatchmakingService(jdbc, new ObjectMapper(), null, null, null, null,
                runtime, null, Files.createTempDirectory("matchmaking-report-access").toString());
        jdbc.update("INSERT INTO users (username,password_hash,role,credits,enabled) VALUES ('alice','x','USER',0,TRUE)");
        jdbc.update("INSERT INTO users (username,password_hash,role,credits,enabled) VALUES ('bob','x','USER',0,TRUE)");
        jdbc.update("INSERT INTO users (username,password_hash,role,credits,enabled) VALUES ('root','x','ROOT',0,TRUE)");
        alice = user("alice", "USER");
        bob = user("bob", "USER");
        root = user("root", "ROOT");
    }

    @Test
    void rootListsAndReadsReportsAcrossUsersWithoutReceivingDeletePermission() throws Exception {
        insertReport("alice-report", alice.id(), "上海", Instant.parse("2026-08-30T01:00:00Z"));
        insertReport("bob-report", bob.id(), "杭州", Instant.parse("2026-08-30T02:00:00Z"));

        List<Map<String, Object>> reports = service.reportSummaries(root);

        assertEquals(List.of("bob", "alice"), reports.stream().map(row -> row.get("ownerUsername")).toList());
        assertTrue(reports.stream().noneMatch(row -> Boolean.TRUE.equals(row.get("viewerCanDelete"))));
        Map<String, Object> bobReport = service.report(root, "bob-report");
        assertEquals("bob", bobReport.get("ownerUsername"));
        assertEquals(false, bobReport.get("viewerCanDelete"));
        assertEquals("bob-report", bobReport.get("id"));
        assertThrows(AuthException.class, () -> service.deleteReport(root, "bob-report"));
    }

    @Test
    void regularUsersOnlyListAndReadTheirOwnReports() throws Exception {
        insertReport("alice-report", alice.id(), "上海", Instant.parse("2026-08-30T01:00:00Z"));
        insertReport("bob-report", bob.id(), "杭州", Instant.parse("2026-08-30T02:00:00Z"));

        List<Map<String, Object>> reports = service.reportSummaries(alice);

        assertEquals(List.of("alice-report"), reports.stream().map(row -> row.get("id")).toList());
        assertEquals(true, reports.get(0).get("viewerCanDelete"));
        assertEquals(true, service.report(alice, "alice-report").get("viewerCanDelete"));
        assertThrows(AuthException.class, () -> service.report(alice, "bob-report"));
    }

    @Test
    void quantityRetentionKeepsNewestReportsPerUserThenAcrossTheSite() throws Exception {
        runtime.update(Map.of("matchmakingRetention", Map.of("maxPerUser", 2, "maxTotal", 3)));
        insertReport("alice-old", alice.id(), "上海", Instant.parse("2026-08-30T01:00:00Z"));
        insertReport("bob-old", bob.id(), "杭州", Instant.parse("2026-08-30T02:00:00Z"));
        insertReport("alice-middle", alice.id(), "上海", Instant.parse("2026-08-30T03:00:00Z"));
        insertReport("bob-new", bob.id(), "杭州", Instant.parse("2026-08-30T04:00:00Z"));
        insertReport("alice-new", alice.id(), "上海", Instant.parse("2026-08-30T05:00:00Z"));

        service.pruneReportsByConfiguredLimits();

        List<String> remaining = jdbc.queryForList(
                "SELECT id FROM matchmaking_reports ORDER BY created_at", String.class);
        assertEquals(List.of("alice-middle", "bob-new", "alice-new"), remaining);
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_profiles", Integer.class));
    }

    @Test
    void deletingOneReportRemovesOnlyItsQuestionnaire() throws Exception {
        insertReport("alice-report", alice.id(), "上海", Instant.now());
        insertReport("bob-report", bob.id(), "杭州", Instant.now());
        service.deleteReport(alice, "alice-report");
        assertEquals(List.of("profile-bob-report"), jdbc.queryForList("SELECT id FROM matchmaking_profiles", String.class));
        assertThrows(AuthException.class, () -> service.deleteReport(alice, "bob-report"));
    }

    private AuthUser user(String username, String role) {
        long id = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
        return new AuthUser(id, username, role, 0, true);
    }

    private void insertReport(String id, long userId, String city, Instant createdAt) throws Exception {
        String profileId = "profile-" + id;
        Timestamp created = Timestamp.from(createdAt);
        Timestamp expires = Timestamp.from(Instant.now().plus(Duration.ofDays(30)));
        jdbc.update("INSERT INTO matchmaking_profiles(id,user_id,payload,created_at,updated_at,expires_at) VALUES(?,?,?,?,?,?)",
                profileId, userId, "{}", created, created, expires);
        String payload = new ObjectMapper().writeValueAsString(Map.of("id", id, "createdAt", createdAt.toString(), "expiresAt", expires.toInstant().toString()));
        jdbc.update("INSERT INTO matchmaking_reports(id,profile_id,user_id,report_payload,source_version,total_score,level,city,has_image,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, profileId, userId, payload, "test", 80.0, "稳健", city, false, created, expires);
    }
}
