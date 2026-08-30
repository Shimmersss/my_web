package com.web.backen.matchmaking;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import com.web.backen.config.AuthConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockHttpServletRequest;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class MatchmakingTrialAuthTest {
    private JdbcTemplate jdbc;
    private AuthService auth;
    private MatchmakingTrialService trials;
    private long rootId;

    @BeforeEach
    void setUp() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        jdbc = new JdbcTemplate(database);
        AuthConfig config = new AuthConfig();
        config.setRootUsername("root");
        config.setRootPassword("root123");
        auth = new AuthService(jdbc, config);
        auth.bootstrapRoot();
        rootId = jdbc.queryForObject("SELECT id FROM users WHERE username='root'", Long.class);
        trials = new MatchmakingTrialService(
                jdbc,
                auth,
                Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC),
                new SecureRandom());
    }

    @Test
    void firstAndRepeatedRedemptionReuseOneRestrictedUser() {
        String code = String.valueOf(trials.createCode(rootId, "").get("code"));

        MatchmakingTrialService.Redemption first = trials.redeem(code);
        MatchmakingTrialService.Redemption second = trials.redeem(code);

        assertEquals(first.session().user().id(), second.session().user().id());
        assertNotEquals(first.session().token(), second.session().token());
        assertTrue(first.session().user().isMatchmakingTrial());
        assertEquals(0, first.session().user().credits());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role='MATCHMAKING_TRIAL'", Integer.class));
        assertEquals(MatchmakingTrialService.STATUS_CLAIMED, first.access().get("status"));
        assertEquals(true, first.access().get("canGenerate"));

        MockHttpServletRequest request = requestFor(second.session());
        AuthException restricted = assertThrows(AuthException.class, () -> auth.requireUser(request));
        assertEquals(403, restricted.getStatus());
        assertEquals(second.session().user().id(), auth.requireSessionUser(request).id());
    }

    @Test
    void boundCodeCanRestoreAfterInitialExpiryButRevocationStopsEverySession() {
        String code = String.valueOf(trials.createCode(rootId, "").get("code"));
        MatchmakingTrialService.Redemption first = trials.redeem(code);
        long codeId = jdbc.queryForObject(
                "SELECT id FROM matchmaking_trial_codes WHERE guest_user_id=?",
                Long.class,
                first.session().user().id());

        jdbc.update("UPDATE matchmaking_trial_codes SET expires_at=TIMESTAMP '2020-01-01 00:00:00' WHERE id=?", codeId);
        assertEquals(first.session().user().id(), trials.redeem(code).session().user().id());

        trials.updateCode(codeId, false, "2020-01-01T00:00:00Z");
        assertEquals(400, assertThrows(AuthException.class, () -> trials.redeem(code)).getStatus());
        assertEquals(400, assertThrows(AuthException.class,
                () -> trials.requireEnabled(first.session().user().id())).getStatus());
        assertEquals(first.session().user().id(), auth.requireSessionUser(requestFor(first.session())).id());
    }

    private MockHttpServletRequest requestFor(AuthService.AuthSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthService.COOKIE_NAME, session.token()));
        request.addHeader("X-CSRF-Token", session.csrfToken());
        return request;
    }
}
