package com.web.backen.matchmaking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.SecureRandom;

/** Owns the durable, non-billable three-card draw used by one exploration attempt. */
@Service
public class MatchmakingTarotService {
    private static final int DRAW_SIZE = 3;
    private static final long DRAW_TTL_HOURS = 24;
    private static final long DRAW_RATE_WINDOW_SECONDS = 3600;
    private static final int MAX_NEW_DRAWS_PER_WINDOW = 12;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    public MatchmakingTarotService(JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = transactions;
    }

    public Map<String, Object> draw(long userId) {
        return transactions.execute(status -> {
            lockUser(userId);
            Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_tarot_draws WHERE user_id=? AND consumed_task_id IS NOT NULL AND consumed_report_id IS NULL", Integer.class, userId);
            if (pending != null && pending > 0) throw new AuthException(409, "当前牌阵正在生成报告，请等待完成或失败恢复后再抽牌");
            jdbc.update("DELETE FROM matchmaking_tarot_draws WHERE user_id=? AND consumed_task_id IS NULL AND expires_at<=CURRENT_TIMESTAMP", userId);
            List<Map<String, Object>> current = jdbc.queryForList("""
                    SELECT id, deck_version, cards_json, created_at, expires_at
                    FROM matchmaking_tarot_draws
                    WHERE user_id=? AND consumed_task_id IS NULL AND expires_at>CURRENT_TIMESTAMP
                    ORDER BY created_at DESC LIMIT 1
                    """, userId);
            if (!current.isEmpty()) return decode(current.get(0));

            Timestamp rateCutoff = Timestamp.from(Instant.now().minusSeconds(DRAW_RATE_WINDOW_SECONDS));
            Integer recentDraws = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM matchmaking_tarot_draws
                    WHERE user_id=? AND created_at>?
                    """, Integer.class, userId, rateCutoff);
            if (recentDraws != null && recentDraws >= MAX_NEW_DRAWS_PER_WINDOW)
                throw new AuthException(429, "抽牌过于频繁，请稍后再试");

            List<TarotDeck.Card> shuffled = new ArrayList<>(TarotDeck.CARDS);
            for (int i = shuffled.size() - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                TarotDeck.Card card = shuffled.get(i); shuffled.set(i, shuffled.get(j)); shuffled.set(j, card);
            }
            String id = UUID.randomUUID().toString();
            Instant now = Instant.now();
            Timestamp created = Timestamp.from(now), expires = Timestamp.from(now.plusSeconds(DRAW_TTL_HOURS * 3600));
            List<Map<String, Object>> cards = List.of(
                    TarotDeck.view(shuffled.get(0), "present"),
                    TarotDeck.view(shuffled.get(1), "shadow"),
                    TarotDeck.view(shuffled.get(2), "next"));
            try {
                jdbc.update("""
                        INSERT INTO matchmaking_tarot_draws
                            (id,user_id,deck_version,cards_json,created_at,expires_at)
                        VALUES (?,?,?,?,?,?)
                        """, id, userId, TarotDeck.VERSION, mapper.writeValueAsString(cards), created, expires);
            } catch (Exception e) {
                throw new IllegalStateException("牌阵保存失败", e);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("drawId", id); result.put("deckVersion", TarotDeck.VERSION); result.put("drawnAt", now.toString());
            result.put("expiresAt", expires.toInstant().toString()); result.put("cards", cards);
            return result;
        });
    }

    public Map<String, Object> current(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, deck_version, cards_json, created_at, expires_at
                FROM matchmaking_tarot_draws
                WHERE user_id=? AND consumed_task_id IS NULL AND expires_at>CURRENT_TIMESTAMP
                ORDER BY created_at DESC LIMIT 1
                """, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("draw", rows.isEmpty() ? null : decode(rows.get(0)));
        return result;
    }

    /** Must be called inside the report-admission transaction, after the task id is allocated. */
    Map<String, Object> consume(long userId, String drawId, String taskId) {
        lockUser(userId);
        if (drawId == null || !drawId.matches("[0-9a-fA-F-]{36}")) throw new AuthException(400, "请先完成三张抽牌");
        int updated = jdbc.update("""
                UPDATE matchmaking_tarot_draws
                SET consumed_task_id=?
                WHERE id=? AND user_id=? AND consumed_task_id IS NULL AND consumed_report_id IS NULL
                  AND expires_at>CURRENT_TIMESTAMP
                """, taskId, drawId, userId);
        if (updated != 1) throw new AuthException(409, "这组三张牌已失效，请重新抽牌");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, deck_version, cards_json, created_at, expires_at
                FROM matchmaking_tarot_draws WHERE id=? AND user_id=?
                """, drawId, userId);
        if (rows.isEmpty()) throw new AuthException(409, "牌阵读取失败，请重新抽牌");
        return decode(rows.get(0));
    }

    void markReport(String drawId, String taskId, String reportId) {
        if (drawId == null || drawId.isBlank()) return;
        int updated = jdbc.update("""
                UPDATE matchmaking_tarot_draws SET consumed_report_id=?
                WHERE id=? AND consumed_task_id=? AND consumed_report_id IS NULL
                """, reportId, drawId, taskId);
        if (updated != 1) throw new IllegalStateException("牌阵报告关联失败");
    }

    void restoreAfterFailure(String drawId, String taskId) {
        if (drawId == null || drawId.isBlank()) return;
        jdbc.update("""
                UPDATE matchmaking_tarot_draws
                SET consumed_task_id=NULL, expires_at=?
                WHERE id=? AND consumed_task_id=? AND consumed_report_id IS NULL
                """, Timestamp.from(Instant.now().plusSeconds(DRAW_TTL_HOURS * 3600)), drawId, taskId);
    }

    void deleteForReport(String reportId) {
        jdbc.update("DELETE FROM matchmaking_tarot_draws WHERE consumed_report_id=?", reportId);
    }

    void deleteForUser(long userId) {
        jdbc.update("DELETE FROM matchmaking_tarot_draws WHERE user_id=?", userId);
    }

    void cleanExpired() {
        jdbc.update("DELETE FROM matchmaking_tarot_draws WHERE expires_at<=CURRENT_TIMESTAMP AND consumed_task_id IS NULL AND consumed_report_id IS NULL");
        jdbc.update("""
                DELETE FROM matchmaking_tarot_draws
                WHERE consumed_report_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM matchmaking_reports r WHERE r.id=matchmaking_tarot_draws.consumed_report_id)
                """);
    }

    private void lockUser(long userId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, userId);
        if (ids.size() != 1) throw new AuthException(404, "用户不存在");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decode(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("drawId", row.get("id")); result.put("deckVersion", row.get("deck_version"));
        Object created = row.get("created_at"), expires = row.get("expires_at");
        result.put("drawnAt", created instanceof Timestamp t ? t.toInstant().toString() : String.valueOf(created));
        result.put("expiresAt", expires instanceof Timestamp t ? t.toInstant().toString() : String.valueOf(expires));
        try {
            List<Map<String, Object>> cards = mapper.readValue(String.valueOf(row.get("cards_json")), new TypeReference<>() {});
            result.put("cards", cards);
        } catch (Exception e) { throw new IllegalStateException("牌阵载荷无法读取", e); }
        return result;
    }
}
