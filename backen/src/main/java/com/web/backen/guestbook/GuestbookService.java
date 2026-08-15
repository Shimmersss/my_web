package com.web.backen.guestbook;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable, bounded guestbook storage.  All browser-visible content remains plain text. */
@Service
public class GuestbookService {
    public static final int PAGE_SIZE = 20;
    private static final int NOTIFICATION_LIMIT = 100;
    private static final int POST_COOLDOWN_SECONDS = 10;

    private final JdbcTemplate jdbc;

    public GuestbookService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> messages(int requestedPage, AuthUser viewer) {
        return pageEntries(null, requestedPage, viewer, true);
    }

    public Map<String, Object> replies(long messageId, int requestedPage, AuthUser viewer) {
        requireMessage(messageId);
        return pageEntries(messageId, requestedPage, viewer, false);
    }

    @Transactional
    public Map<String, Object> createMessage(AuthUser author, String content) {
        return entry(create(author, null, content), author);
    }

    @Transactional
    public Map<String, Object> createReply(AuthUser author, long messageId, String content) {
        requireMessage(messageId);
        long id = create(author, messageId, content);
        Map<String, Object> parent = entryRow(messageId, author);
        long recipient = number(parent.get("authorId"));
        if (recipient != author.id()) notify(recipient, author.id(), id, "REPLY");
        return entry(id, author);
    }

    @Transactional
    public Map<String, Object> like(long entryId, AuthUser actor) {
        Map<String, Object> target = entryRow(entryId, actor);
        if (number(target.get("authorId")) == actor.id()) throw new AuthException(400, "不能点赞自己的留言");
        try {
            jdbc.update("INSERT INTO guestbook_likes (entry_id, user_id) VALUES (?, ?)", entryId, actor.id());
            notify(number(target.get("authorId")), actor.id(), entryId, "LIKE");
        } catch (DuplicateKeyException ignored) {
            // The unique index makes repeated clicks idempotent.
        }
        return entry(entryId, actor);
    }

    @Transactional
    public Map<String, Object> unlike(long entryId, AuthUser actor) {
        requireEntry(entryId);
        jdbc.update("DELETE FROM guestbook_likes WHERE entry_id=? AND user_id=?", entryId, actor.id());
        return entry(entryId, actor);
    }

    @Transactional
    public void delete(long entryId, AuthUser actor) {
        Map<String, Object> target = entryRow(entryId, actor);
        if (number(target.get("authorId")) != actor.id() && !actor.isRoot()) {
            throw new AuthException(403, "只能删除自己的留言");
        }
        jdbc.update("DELETE FROM guestbook_entries WHERE id=?", entryId);
    }

    public Map<String, Object> context(long entryId, AuthUser viewer) {
        Map<String, Object> target = entry(entryId, viewer);
        long parentId = number(target.get("parentId"));
        long rootId = parentId == 0 ? entryId : parentId;
        Map<String, Object> root = rootId == entryId ? target : entry(rootId, viewer);
        return Map.of("entry", target, "rootEntry", root, "rootId", rootId);
    }

    public Map<String, Object> adminEntries(String type, int requestedPage, AuthUser viewer) {
        String normalized = type == null ? "all" : type.trim().toLowerCase();
        String predicate = switch (normalized) {
            case "message", "messages" -> "e.parent_id IS NULL";
            case "reply", "replies" -> "e.parent_id IS NOT NULL";
            case "all", "" -> "1=1";
            default -> throw new AuthException(400, "留言类型无效");
        };
        int page = page(requestedPage);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM guestbook_entries e WHERE " + predicate, Integer.class);
        List<Map<String, Object>> items = normalizedRows(jdbc.queryForList(entrySql(predicate, "e.created_at DESC, e.id DESC") + " LIMIT ? OFFSET ?",
                viewer == null ? -1 : viewer.id(), PAGE_SIZE, (page - 1) * PAGE_SIZE));
        decorateEntries(items, viewer);
        for (Map<String, Object> item : items) item.put("content", summary(String.valueOf(item.get("content"))));
        return pageResult(items, page, count == null ? 0 : count);
    }

    public Map<String, Object> notifications(AuthUser user, int requestedPage) {
        int page = page(requestedPage);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM guestbook_notifications WHERE recipient_id=?", Integer.class, user.id());
        List<Map<String, Object>> items = normalizedRows(jdbc.queryForList("""
                SELECT n.id, n.notification_type AS type, n.entry_id AS entryId, n.created_at AS createdAt, n.read_at AS readAt,
                       actor.username AS actorUsername, e.parent_id AS parentId, e.content AS content
                FROM guestbook_notifications n
                JOIN users actor ON actor.id=n.actor_id
                LEFT JOIN guestbook_entries e ON e.id=n.entry_id
                WHERE n.recipient_id=?
                ORDER BY n.created_at DESC, n.id DESC
                LIMIT ? OFFSET ?
                """, user.id(), PAGE_SIZE, (page - 1) * PAGE_SIZE));
        for (Map<String, Object> item : items) item.put("rootId", number(item.get("parentId")) == 0 ? number(item.get("entryId")) : number(item.get("parentId")));
        return pageResult(items, page, count == null ? 0 : count);
    }

    public int unreadCount(AuthUser user) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM guestbook_notifications WHERE recipient_id=? AND read_at IS NULL", Integer.class, user.id());
        return count == null ? 0 : count;
    }

    @Transactional
    public void markRead(long notificationId, AuthUser user) {
        List<Long> recipients = jdbc.queryForList("SELECT recipient_id FROM guestbook_notifications WHERE id=?", Long.class, notificationId);
        if (recipients.isEmpty()) throw new AuthException(404, "通知不存在");
        if (recipients.get(0) != user.id()) throw new AuthException(403, "无权操作该通知");
        jdbc.update("UPDATE guestbook_notifications SET read_at=CURRENT_TIMESTAMP WHERE id=? AND read_at IS NULL", notificationId);
    }

    @Transactional
    public void markAllRead(AuthUser user) {
        jdbc.update("UPDATE guestbook_notifications SET read_at=CURRENT_TIMESTAMP WHERE recipient_id=? AND read_at IS NULL", user.id());
    }

    private long create(AuthUser author, Long parentId, String rawContent) {
        String content = normalizeContent(rawContent, parentId == null ? 1000 : 500);
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, author.id());
        Integer recent = jdbc.queryForObject("SELECT COUNT(*) FROM guestbook_entries WHERE author_id=? AND created_at>=?", Integer.class,
                author.id(), Timestamp.from(Instant.now().minusSeconds(POST_COOLDOWN_SECONDS)));
        if (recent != null && recent > 0) throw new AuthException(429, "发布过于频繁，请 10 秒后再试");
        jdbc.update("INSERT INTO guestbook_entries (parent_id, author_id, content) VALUES (?, ?, ?)", parentId, author.id(), content);
        Long id = jdbc.queryForObject("SELECT id FROM guestbook_entries WHERE author_id=? ORDER BY id DESC LIMIT 1", Long.class, author.id());
        if (id == null) throw new IllegalStateException("留言保存失败");
        return id;
    }

    private void notify(long recipientId, long actorId, long entryId, String type) {
        if (recipientId == actorId) return;
        try {
            jdbc.update("INSERT INTO guestbook_notifications (recipient_id, actor_id, entry_id, notification_type) VALUES (?, ?, ?, ?)",
                    recipientId, actorId, entryId, type);
        } catch (DuplicateKeyException ignored) {
            return;
        }
        jdbc.update("""
                DELETE FROM guestbook_notifications
                WHERE recipient_id=? AND id NOT IN (
                    SELECT id FROM (
                        SELECT id FROM guestbook_notifications WHERE recipient_id=? ORDER BY created_at DESC, id DESC LIMIT ?
                    ) recent_notifications
                )
                """, recipientId, recipientId, NOTIFICATION_LIMIT);
    }

    private Map<String, Object> pageEntries(Long parentId, int requestedPage, AuthUser viewer, boolean messages) {
        int page = page(requestedPage);
        String predicate = messages ? "e.parent_id IS NULL" : "e.parent_id=?";
        Integer count = messages
                ? jdbc.queryForObject("SELECT COUNT(*) FROM guestbook_entries e WHERE e.parent_id IS NULL", Integer.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM guestbook_entries e WHERE e.parent_id=?", Integer.class, parentId);
        Object[] args = messages
                ? new Object[]{viewer == null ? -1 : viewer.id(), PAGE_SIZE, (page - 1) * PAGE_SIZE}
                : new Object[]{viewer == null ? -1 : viewer.id(), parentId, PAGE_SIZE, (page - 1) * PAGE_SIZE};
        String order = messages ? "e.created_at DESC, e.id DESC" : "e.created_at ASC, e.id ASC";
        List<Map<String, Object>> items = normalizedRows(jdbc.queryForList(entrySql(predicate, order) + " LIMIT ? OFFSET ?", args));
        decorateEntries(items, viewer);
        return pageResult(items, page, count == null ? 0 : count);
    }

    private String entrySql(String predicate, String order) {
        return """
                SELECT e.id, e.parent_id AS parentId, e.author_id AS authorId, u.username, e.content, e.created_at AS createdAt,
                       (SELECT COUNT(*) FROM guestbook_likes l WHERE l.entry_id=e.id) AS likeCount,
                       (SELECT COUNT(*) FROM guestbook_entries r WHERE r.parent_id=e.id) AS replyCount,
                       CASE WHEN EXISTS (SELECT 1 FROM guestbook_likes mine WHERE mine.entry_id=e.id AND mine.user_id=?) THEN TRUE ELSE FALSE END AS likedByMe
                FROM guestbook_entries e JOIN users u ON u.id=e.author_id
                WHERE %s
                ORDER BY %s
                """.formatted(predicate, order);
    }

    private Map<String, Object> entry(long entryId, AuthUser viewer) {
        Map<String, Object> row = entryRow(entryId, viewer);
        row.put("canDelete", viewer != null && (viewer.isRoot() || number(row.get("authorId")) == viewer.id()));
        return row;
    }

    private void decorateEntries(List<Map<String, Object>> entries, AuthUser viewer) {
        for (Map<String, Object> entry : entries) {
            entry.put("canDelete", viewer != null && (viewer.isRoot() || number(entry.get("authorId")) == viewer.id()));
        }
    }

    private Map<String, Object> entryRow(long entryId, AuthUser viewer) {
        List<Map<String, Object>> rows = normalizedRows(jdbc.queryForList(entrySql("e.id=?", "e.id DESC"), viewer == null ? -1 : viewer.id(), entryId));
        if (rows.isEmpty()) throw new AuthException(404, "留言不存在或已删除");
        return new LinkedHashMap<>(rows.get(0));
    }

    private void requireEntry(long entryId) { entryRow(entryId, null); }

    private void requireMessage(long entryId) {
        List<Map<String, Object>> rows = normalizedRows(jdbc.queryForList("SELECT parent_id FROM guestbook_entries WHERE id=?", entryId));
        if (rows.isEmpty()) throw new AuthException(404, "留言不存在或已删除");
        if (rows.get(0).get("parentId") != null) throw new AuthException(400, "只能回复主留言");
    }

    private Map<String, Object> pageResult(List<Map<String, Object>> items, int page, int total) {
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
        return Map.of("items", items, "page", page, "pageSize", PAGE_SIZE, "total", total, "totalPages", totalPages);
    }

    private int page(int value) { return Math.max(1, value); }

    /** JDBC drivers disagree on the case of unquoted aliases; expose one stable API shape. */
    private List<Map<String, Object>> normalizedRows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::normalizedRow).toList();
    }

    private Map<String, Object> normalizedRow(Map<String, Object> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        source.forEach((key, value) -> normalized.put(normalizedKey(key), value));
        return normalized;
    }

    private String normalizedKey(String key) {
        return switch (key == null ? "" : key.toUpperCase()) {
            case "ID" -> "id";
            case "PARENT_ID", "PARENTID" -> "parentId";
            case "AUTHOR_ID", "AUTHORID" -> "authorId";
            case "USERNAME" -> "username";
            case "CONTENT" -> "content";
            case "CREATED_AT", "CREATEDAT" -> "createdAt";
            case "LIKE_COUNT", "LIKECOUNT" -> "likeCount";
            case "REPLY_COUNT", "REPLYCOUNT" -> "replyCount";
            case "LIKED_BY_ME", "LIKEDBYME" -> "likedByMe";
            case "NOTIFICATION_TYPE", "TYPE" -> "type";
            case "ENTRY_ID", "ENTRYID" -> "entryId";
            case "READ_AT", "READAT" -> "readAt";
            case "ACTOR_USERNAME", "ACTORUSERNAME" -> "actorUsername";
            default -> key;
        };
    }

    private long number(Object value) { return value instanceof Number number ? number.longValue() : 0L; }

    private String summary(String content) {
        return content.codePointCount(0, content.length()) <= 160
                ? content
                : content.substring(0, content.offsetByCodePoints(0, 157)) + "…";
    }

    private String normalizeContent(String value, int maxCodePoints) {
        String content = (value == null ? "" : value).replace("\r\n", "\n").replace('\r', '\n').trim();
        if (content.isBlank()) throw new AuthException(400, "留言内容不能为空");
        if (content.codePointCount(0, content.length()) > maxCodePoints) {
            throw new AuthException(400, "留言内容不能超过 " + maxCodePoints + " 个字符");
        }
        return content;
    }
}
