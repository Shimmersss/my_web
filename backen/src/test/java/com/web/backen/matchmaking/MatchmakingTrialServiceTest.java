package com.web.backen.matchmaking;

import com.web.backen.auth.AuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchmakingTrialServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-31T02:00:00Z");

    private JdbcTemplate jdbc;
    private MatchmakingTrialService service;
    private long rootId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build());
        jdbc.update("INSERT INTO users (username,password_hash,role,credits,enabled) VALUES ('root','x','ROOT',0,TRUE)");
        rootId = jdbc.queryForObject("SELECT id FROM users WHERE username='root'", Long.class);
        service = new MatchmakingTrialService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
    }

    @Test
    void createsHashedCodeWithSevenDayDefaultAndNeverListsPlaintext() {
        Map<String, Object> created = service.createCode(rootId, "");
        String code = String.valueOf(created.get("code"));

        assertTrue(code.matches("MM-[A-Z2-9]{24}"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM matchmaking_trial_codes WHERE code_hash=?",
                Integer.class, code));
        assertEquals(64, jdbc.queryForObject(
                "SELECT LENGTH(code_hash) FROM matchmaking_trial_codes",
                Integer.class));
        Map<String, Object> stored = service.codes().get(0);
        assertFalse(stored.containsKey("code"));
        assertEquals(code.substring(code.length() - 4), stored.get("codeSuffix"));
        assertEquals("UNUSED", stored.get("status"));
        assertEquals(NOW.plus(Duration.ofDays(7)), ((Timestamp) stored.get("expiresAt")).toInstant());
    }

    @Test
    void redeemRecordNormalizesInputAndReturnsNoCodeHash() {
        String code = String.valueOf(service.createCode(rootId, "").get("code"));

        Map<String, Object> record = service.redeemRecord("  " + code.toLowerCase() + "  ");

        assertEquals(code.substring(code.length() - 4), record.get("codeSuffix"));
        assertEquals("UNUSED", record.get("status"));
        assertFalse(record.containsKey("code"));
        assertFalse(record.containsKey("codeHash"));
    }

    @Test
    void rejectsUnknownExpiredRevokedAndRegistrationCodesWithOnePublicError() {
        String expired = String.valueOf(service.createCode(rootId, NOW.minusSeconds(1).toString()).get("code"));
        Map<String, Object> revokedCreated = service.createCode(rootId, "");
        String revoked = String.valueOf(revokedCreated.get("code"));
        long revokedId = ((Number) service.codes().get(0).get("id")).longValue();
        service.updateCode(revokedId, false, NOW.plus(Duration.ofDays(7)).toString());
        String registrationOnly = "MM-ABCDEFGHJKLMNPQRSTUVWXYZ23";
        jdbc.update("""
                INSERT INTO invite_codes (code, credits, max_uses, created_by, expires_at)
                VALUES (?, 0, 1, ?, ?)
                """, registrationOnly, rootId, Timestamp.from(NOW.plus(Duration.ofDays(7))));

        assertInvalid("not-a-code");
        assertInvalid(expired);
        assertInvalid(revoked);
        assertInvalid(registrationOnly);
    }

    @Test
    void updateRejectsMalformedExpiryWithoutChangingStoredDeadline() {
        service.createCode(rootId, "");
        Map<String, Object> before = service.codes().get(0);
        long id = ((Number) before.get("id")).longValue();

        AuthException error = assertThrows(AuthException.class,
                () -> service.updateCode(id, false, "31-08-2026"));

        assertEquals(400, error.getStatus());
        assertEquals("过期时间格式无效", error.getMessage());
        Map<String, Object> after = service.codes().get(0);
        assertEquals(before.get("expiresAt"), after.get("expiresAt"));
        assertEquals(true, after.get("enabled"));
    }

    @Test
    void accessForUserReturnsNullableSafeSummaryForBoundGuest() {
        service.createCode(rootId, "");
        jdbc.update("INSERT INTO users (username,password_hash,role,credits,enabled) VALUES ('trial','x','MATCHMAKING_TRIAL',0,TRUE)");
        long guestId = jdbc.queryForObject("SELECT id FROM users WHERE username='trial'", Long.class);
        jdbc.update("UPDATE matchmaking_trial_codes SET guest_user_id=?, status='CLAIMED'", guestId);

        Map<String, Object> access = service.accessForUser(guestId);

        assertEquals(guestId, access.get("guestUserId"));
        assertEquals("CLAIMED", access.get("status"));
        assertNull(access.get("activeTaskId"));
        assertEquals("", access.get("reportId"));
        assertEquals(true, access.get("canGenerate"));
        assertEquals(false, access.get("reportAvailable"));
        assertFalse(access.containsKey("codeHash"));
    }

    @Test
    void boundCodeRemainsRecoverableAfterFirstRedemptionDeadline() {
        String code = String.valueOf(service.createCode(rootId, NOW.minusSeconds(1).toString()).get("code"));
        jdbc.update("INSERT INTO users (username,password_hash,role,credits,enabled) VALUES ('bound','x','MATCHMAKING_TRIAL',0,TRUE)");
        long guestId = jdbc.queryForObject("SELECT id FROM users WHERE username='bound'", Long.class);
        jdbc.update("UPDATE matchmaking_trial_codes SET guest_user_id=?, status='CLAIMED'", guestId);

        assertEquals(guestId, service.redeemRecord(code).get("guestUserId"));
    }

    private void assertInvalid(String code) {
        AuthException error = assertThrows(AuthException.class, () -> service.redeemRecord(code));
        assertEquals(400, error.getStatus());
        assertEquals("内测邀请码无效、已过期或已撤销", error.getMessage());
    }
}
