package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthService;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.config.AuthConfig;
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

class MatchmakingTrialAdminTest {
    private JdbcTemplate jdbc;
    private QuotaService quota;
    private MatchmakingTrialService trials;
    private MatchmakingService matchmaking;
    private AuthUser root;
    private AuthUser trialUser;
    private String fullCode;
    private String suffix;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build());
        AuthConfig config = new AuthConfig();
        config.setRootUsername("root");
        config.setRootPassword("root123");
        AuthService auth = new AuthService(jdbc, config);
        auth.bootstrapRoot();
        jdbc.update("INSERT INTO users(username,password_hash,role,credits,enabled) VALUES('alice','x','USER',5,TRUE)");
        root = auth.refreshUser(jdbc.queryForObject("SELECT id FROM users WHERE username='root'", Long.class));
        trials = new MatchmakingTrialService(jdbc, auth);
        Map<String, Object> created = trials.createCode(root.id(), "");
        fullCode = String.valueOf(created.get("code"));
        suffix = String.valueOf(created.get("codeSuffix"));
        trialUser = trials.redeem(fullCode).session().user();
        quota = new QuotaService(jdbc);
        quota.initializeDefaults();
        matchmaking = new MatchmakingService(jdbc, new ObjectMapper(), null, quota, null, null,
                null, trials, Files.createTempDirectory("matchmaking-trial-admin").toString());
    }

    @Test
    void hidesTrialUsersFromAccountOperationsAndListsCompleteCodeForRootAdmin() {
        assertEquals(List.of("root", "alice"), quota.users().stream()
                .map(row -> String.valueOf(row.get("username"))).toList());
        assertEquals(2, quota.stats().get("users"));
        assertEquals(2, quota.stats().get("activeUsers"));
        Map<String, Object> code = trials.codes().get(0);
        assertEquals(suffix, code.get("codeSuffix"));
        assertEquals(fullCode, code.get("code"));
        assertEquals(true, code.get("codeRecoverable"));
        assertFalse(code.containsKey("codeHash"));
    }

    @Test
    void rootSeesOpaqueTrialOwnerLabelWhileTrialRetainsOwnership() throws Exception {
        insertReport("trial-report", trialUser.id());
        jdbc.update("UPDATE matchmaking_trial_codes SET status='COMPLETED', report_id='trial-report' WHERE guest_user_id=?",
                trialUser.id());

        Map<String, Object> summary = matchmaking.reportSummaries(root).get(0);
        assertEquals("内测访客 · ****" + suffix, summary.get("ownerLabel"));
        assertFalse(String.valueOf(summary).contains(trialUser.username()));
        assertEquals("内测访客 · ****" + suffix,
                matchmaking.report(root, "trial-report").get("ownerLabel"));
        assertEquals(true, matchmaking.report(trialUser, "trial-report").get("viewerCanDelete"));
    }

    private void insertReport(String id, long userId) throws Exception {
        String profileId = "profile-" + id;
        Timestamp expires = Timestamp.from(Instant.now().plus(Duration.ofDays(30)));
        jdbc.update("INSERT INTO matchmaking_profiles(id,user_id,payload,expires_at) VALUES(?,?,?,?)",
                profileId, userId, "{}", expires);
        String payload = new ObjectMapper().writeValueAsString(Map.of("id", id));
        jdbc.update("""
                INSERT INTO matchmaking_reports
                    (id,profile_id,user_id,report_payload,source_version,total_score,level,city,has_image,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, id, profileId, userId, payload, "test", 80.0, "稳健", "上海", false, expires);
    }
}
