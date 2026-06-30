package com.web.backen.auth;

import com.web.backen.config.AuthConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AuthQuotaServiceTest {

    @Test
    void bootstrapsRootAndAuthenticatesWithCookieAndCsrf() {
        TestServices services = newServices();

        AuthService.AuthSession session = services.auth().login("root", "root123");
        assertTrue(session.user().isRoot());
        assertNotNull(session.csrfToken());

        MockHttpServletRequest request = requestWithSession(session);
        assertEquals("root", services.auth().requireRoot(request).username());
        assertDoesNotThrow(() -> services.auth().requireCsrf(request));

        MockHttpServletRequest badCsrf = new MockHttpServletRequest();
        badCsrf.setCookies(new Cookie(AuthService.COOKIE_NAME, session.token()));
        badCsrf.addHeader("X-CSRF-Token", "bad");
        assertEquals(403, assertThrows(AuthException.class, () -> services.auth().requireCsrf(badCsrf)).getStatus());
    }

    @Test
    void inviteRegistrationConsumesInviteAndRejectsReuse() {
        TestServices services = newServices();
        services.quota().createInvite(1L, "invite-one", 12, 1);

        AuthUser user = services.auth().register("alice", "alice-password-123", "invite-one");
        assertEquals(12, user.credits());
        assertEquals(1, services.jdbc().queryForObject("SELECT used_count FROM invite_codes WHERE code='invite-one'", Integer.class));

        AuthException reused = assertThrows(AuthException.class,
                () -> services.auth().register("bob", "bob-password-123", "invite-one"));
        assertEquals(400, reused.getStatus());
    }

    @Test
    void inviteRegistrationConsumesInviteAtomically() throws Exception {
        TestServices services = newServices();
        services.quota().createInvite(1L, "race-invite", 12, 1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger invalidInvite = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);

        for (String username : new String[]{"alice", "bob"}) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    services.auth().register(username, username + "-password-123", "race-invite");
                    success.incrementAndGet();
                } catch (AuthException e) {
                    if (e.getStatus() == 400) invalidInvite.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, success.get());
        assertEquals(1, invalidInvite.get());
        assertEquals(1, services.jdbc().queryForObject("SELECT used_count FROM invite_codes WHERE code='race-invite'", Integer.class));
    }

    @Test
    void quotaSpendIsAtomicAndRefundsOnce() {
        TestServices services = newServices();
        services.jdbc().update("INSERT INTO users (username, password_hash, role, credits, enabled) VALUES ('alice', 'x', 'USER', 5, TRUE)");
        long userId = services.jdbc().queryForObject("SELECT id FROM users WHERE username='alice'", Long.class);

        long tx = services.quota().spend(userId, 3, "TRANSLATION", "task-1", "test spend");
        assertEquals(2, services.quota().balance(userId));

        AuthException insufficient = assertThrows(AuthException.class,
                () -> services.quota().spend(userId, 3, "PPT", "task-2", "test spend"));
        assertEquals(402, insufficient.getStatus());

        services.quota().refund(tx, "test refund");
        services.quota().refund(tx, "duplicate refund ignored");
        assertEquals(5, services.quota().balance(userId));
    }

    @Test
    void settingsCanBeUpdated() {
        TestServices services = newServices();
        services.quota().updateSettings(2, 15);

        Map<String, Object> settings = services.quota().settings();
        assertEquals(2, settings.get("translationCreditPerPage"));
        assertEquals(15, settings.get("pptCreditPerTask"));
    }

    private TestServices newServices() {
        EmbeddedDatabase db = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(db);
        AuthConfig config = new AuthConfig();
        config.setRootUsername("root");
        config.setRootPassword("root123");
        AuthService auth = new AuthService(jdbc, config);
        auth.bootstrapRoot();
        QuotaService quota = new QuotaService(jdbc);
        quota.initializeDefaults();
        return new TestServices(db, jdbc, auth, quota);
    }

    private MockHttpServletRequest requestWithSession(AuthService.AuthSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthService.COOKIE_NAME, session.token()));
        request.addHeader("X-CSRF-Token", session.csrfToken());
        return request;
    }

    private record TestServices(EmbeddedDatabase db, JdbcTemplate jdbc, AuthService auth, QuotaService quota) {}
}
