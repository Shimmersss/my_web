package com.web.backen.guestbook;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class GuestbookServiceTest {
    @Test
    void supportsMessagesRepliesLikesNotificationsAndCascadeDelete() throws Exception {
        TestServices services = newServices();
        Map<String, Object> message = services.service().createMessage(services.alice(), "  <b>第一条</b>\r\n留言  ");
        assertEquals("<b>第一条</b>\n留言", message.get("content"));
        long messageId = id(message);

        Thread.sleep(15);
        Map<String, Object> reply = services.service().createReply(services.bob(), messageId, "收到！");
        long replyId = id(reply);
        List<Map<String, Object>> currentMessages = (List<Map<String, Object>>) services.service().messages(1, services.alice()).get("items");
        assertEquals(1, currentMessages.size());
        assertEquals(1, ((Number) currentMessages.get(0).get("replyCount")).intValue());

        services.service().like(messageId, services.bob());
        services.service().like(replyId, services.alice());
        assertEquals(2, services.service().unreadCount(services.alice()));
        assertThrows(AuthException.class, () -> services.service().like(messageId, services.alice()));

        Map<String, Object> notificationPage = services.service().notifications(services.alice(), 1);
        Map<String, Object> first = ((List<Map<String, Object>>) notificationPage.get("items")).get(0);
        services.service().markRead(id(first), services.alice());
        assertEquals(1, services.service().unreadCount(services.alice()));
        assertThrows(AuthException.class, () -> services.service().markRead(id(first), services.bob()));

        assertThrows(AuthException.class, () -> services.service().delete(messageId, services.bob()));
        services.service().delete(messageId, services.alice());
        assertEquals(0, count(services.jdbc(), "guestbook_entries"));
        assertEquals(0, count(services.jdbc(), "guestbook_likes"));
        assertEquals(0, count(services.jdbc(), "guestbook_notifications"));
    }

    @Test
    void rejectsNestedRepliesAndUsesBoundedPages() throws Exception {
        TestServices services = newServices();
        long root = id(services.service().createMessage(services.alice(), "root"));
        Thread.sleep(15);
        long reply = id(services.service().createReply(services.bob(), root, "reply"));
        Thread.sleep(15);
        assertEquals(400, assertThrows(AuthException.class, () -> services.service().createReply(services.alice(), reply, "nested")).getStatus());
        assertEquals(1, ((List<?>) services.service().replies(root, 1, services.alice()).get("items")).size());
    }

    @Test
    void paginatesMessagesAndRetainsOnlyLatestHundredNotifications() {
        TestServices services = newServices();
        for (int i = 1; i <= 21; i++) insertEntry(services, services.alice().id(), null, "message-" + i);
        Map<String, Object> firstPage = services.service().messages(1, services.bob());
        Map<String, Object> secondPage = services.service().messages(2, services.bob());
        assertEquals(20, ((List<?>) firstPage.get("items")).size());
        assertEquals(1, ((List<?>) secondPage.get("items")).size());
        assertEquals("message-21", ((List<Map<String, Object>>) firstPage.get("items")).get(0).get("content"));

        for (int i = 0; i < 101; i++) {
            long entryId = insertEntry(services, services.alice().id(), null, "notify-" + i);
            services.service().like(entryId, services.bob());
        }
        assertEquals(100, services.service().unreadCount(services.alice()));
        assertEquals(100, services.service().notifications(services.alice(), 1).get("total"));
        services.service().markAllRead(services.alice());
        assertEquals(0, services.service().unreadCount(services.alice()));
    }

    @Test
    void appliesSharedPublishCooldownAndContentLimits() {
        TestServices services = newServices();
        services.service().createMessage(services.alice(), "first");
        assertEquals(429, assertThrows(AuthException.class, () -> services.service().createMessage(services.alice(), "again")).getStatus());
        String oversized = "x".repeat(1001);
        assertEquals(400, assertThrows(AuthException.class, () -> services.service().createMessage(services.bob(), oversized)).getStatus());
        assertEquals(400, assertThrows(AuthException.class, () -> services.service().createMessage(services.bob(), " \n ")).getStatus());
    }

    @Test
    void concurrentPostsCannotBypassCooldown() throws Exception {
        TestServices services = newServices();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> post = () -> {
                return services.transaction().execute(status -> {
                    try {
                        services.service().createMessage(services.alice(), "parallel");
                        return 200;
                    } catch (AuthException exception) {
                        return exception.getStatus();
                    }
                });
            };
            List<Future<Integer>> results = executor.invokeAll(List.of(post, post));
            assertTrue(results.stream().map(this::result).anyMatch(status -> status == 200));
            assertTrue(results.stream().map(this::result).anyMatch(status -> status == 429));
        } finally {
            executor.shutdownNow();
        }
    }

    private TestServices newServices() {
        EmbeddedDatabase db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO users (username, password_hash, role, credits, enabled) VALUES ('alice', 'x', 'USER', 0, TRUE)");
        jdbc.update("INSERT INTO users (username, password_hash, role, credits, enabled) VALUES ('bob', 'x', 'USER', 0, TRUE)");
        long aliceId = jdbc.queryForObject("SELECT id FROM users WHERE username='alice'", Long.class);
        long bobId = jdbc.queryForObject("SELECT id FROM users WHERE username='bob'", Long.class);
        return new TestServices(db, jdbc, new GuestbookService(jdbc), new TransactionTemplate(new DataSourceTransactionManager(db)),
                user(aliceId, "alice"), user(bobId, "bob"));
    }

    private AuthUser user(long id, String username) { return new AuthUser(id, username, "USER", 0, true); }
    private long insertEntry(TestServices services, long authorId, Long parentId, String content) {
        services.jdbc().update("INSERT INTO guestbook_entries (parent_id, author_id, content) VALUES (?, ?, ?)", parentId, authorId, content);
        return services.jdbc().queryForObject("SELECT MAX(id) FROM guestbook_entries", Long.class);
    }
    private int result(Future<Integer> future) {
        try { return future.get(); } catch (Exception exception) { throw new AssertionError(exception); }
    }
    private long id(Map<String, Object> row) { return ((Number) row.get("id")).longValue(); }
    private int count(JdbcTemplate jdbc, String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private record TestServices(EmbeddedDatabase db, JdbcTemplate jdbc, GuestbookService service, TransactionTemplate transaction,
                                AuthUser alice, AuthUser bob) { }
}
