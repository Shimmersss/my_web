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
    void unusedInviteCanBeDeletedButUsedInviteRemainsAuditable() {
        TestServices services = newServices();
        services.quota().createInvite(1L, "delete-unused", 12, 1);
        long unusedId = services.jdbc().queryForObject("SELECT id FROM invite_codes WHERE code='delete-unused'", Long.class);
        services.quota().deleteUnusedInvite(unusedId);
        assertEquals(0, services.jdbc().queryForObject("SELECT COUNT(*) FROM invite_codes WHERE id=?", Integer.class, unusedId));

        services.quota().createInvite(1L, "keep-used", 12, 1);
        services.auth().register("used-invite-user", "used-invite-password-123", "keep-used");
        long usedId = services.jdbc().queryForObject("SELECT id FROM invite_codes WHERE code='keep-used'", Long.class);
        AuthException error = assertThrows(AuthException.class, () -> services.quota().deleteUnusedInvite(usedId));
        assertEquals(400, error.getStatus());
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
        services.quota().updateSettings(2, 15, false, 4, 4, 3, 6, 9);

        Map<String, Object> settings = services.quota().settings();
        assertEquals(2, settings.get("translationCreditPerPage"));
        assertEquals(15, settings.get("pptCreditPerTask"));
        assertEquals(false, settings.get("dailyCheckinEnabled"));
        assertEquals(4, settings.get("dailyCheckinCredits"));
        assertEquals(3, settings.get("imageLowCredits"));
        assertEquals(6, settings.get("imageMediumCredits"));
        assertEquals(9, settings.get("imageHighCredits"));
        assertEquals(2, settings.get("matchmakingCreditPerReport"));

        services.quota().updateSettings(2, 15, false, 4, 4, 3, 6, 9, 7);
        assertEquals(7, services.quota().settings().get("matchmakingCreditPerReport"));
    }

    @Test
    void dailyCheckinAwardsOncePerShanghaiCalendarDay() {
        TestServices services = newServices();
        services.jdbc().update("INSERT INTO users (username, password_hash, role, credits, enabled) VALUES ('alice', 'x', 'USER', 5, TRUE)");
        long userId = services.jdbc().queryForObject("SELECT id FROM users WHERE username='alice'", Long.class);

        Map<String, Object> first = services.quota().claimDailyCheckin(userId);
        Map<String, Object> again = services.quota().claimDailyCheckin(userId);

        assertEquals(2, first.get("granted"));
        assertEquals(7, first.get("balance"));
        assertEquals(true, again.get("claimed"));
        assertEquals(7, services.quota().balance(userId));
        assertEquals(1, services.jdbc().queryForObject("SELECT COUNT(*) FROM credit_transactions WHERE user_id=? AND kind='DAILY_CHECKIN'", Integer.class, userId));
    }

    @Test
    void concurrentRefundsCreditExactlyOnceInRealTransactions() throws Exception {
        TestServices services = newServices();
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(services.db()));
        services.jdbc().update("UPDATE users SET credits=10 WHERE id=1");
        long spend = tx.execute(status -> services.quota().spend(1, 3, "TEST", "concurrent", "test"));
        var pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                try { assertTrue(start.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                tx.executeWithoutResult(status -> services.quota().refund(spend, "concurrent retry"));
            }));
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(10, services.quota().balance(1));
            assertEquals(1, services.jdbc().queryForObject("SELECT COUNT(*) FROM credit_transactions WHERE kind='REFUND'", Integer.class));
            assertEquals(1, services.jdbc().queryForObject("SELECT COUNT(*) FROM credit_refund_claims", Integer.class));
        } finally { pool.shutdownNow(); services.db().shutdown(); }
    }

    @Test
    void rolledBackRefundDoesNotLeaveClaimOrCredit() {
        TestServices services = newServices();
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(services.db()));
        services.jdbc().update("UPDATE users SET credits=10 WHERE id=1");
        long spend = tx.execute(status -> services.quota().spend(1, 3, "TEST", "rollback", "test"));
        tx.executeWithoutResult(status -> { services.quota().refund(spend, "rollback"); status.setRollbackOnly(); });
        assertEquals(7, services.quota().balance(1));
        assertEquals(0, services.jdbc().queryForObject("SELECT COUNT(*) FROM credit_refund_claims", Integer.class));
        tx.executeWithoutResult(status -> services.quota().refund(spend, "retry"));
        assertEquals(10, services.quota().balance(1));
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
