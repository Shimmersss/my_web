package com.web.backen.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class QuotaService {
    private final JdbcTemplate jdbc;

    public QuotaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initializeDefaults() {
        ensureSetting("translation.credit_per_page", "1");
        ensureSetting("ppt.credit_per_task", "10");
        ensureSetting("daily_checkin.enabled", "true");
        ensureSetting("daily_checkin.credits", "2");
        ensureSetting("daily_checkin.min_credits", String.valueOf(dailyCheckinCredits()));
        ensureSetting("daily_checkin.max_credits", String.valueOf(dailyCheckinCredits()));
    }

    public int translationCreditPerPage() {
        return intSetting("translation.credit_per_page", 1);
    }

    public int pptCreditPerTask() {
        return intSetting("ppt.credit_per_task", 10);
    }

    public Map<String, Object> settings() {
        return Map.of(
                "translationCreditPerPage", translationCreditPerPage(),
                "pptCreditPerTask", pptCreditPerTask(),
                "dailyCheckinEnabled", dailyCheckinEnabled(),
                "dailyCheckinCredits", dailyCheckinCredits(),
                "dailyCheckinMinCredits", dailyCheckinMinCredits(),
                "dailyCheckinMaxCredits", dailyCheckinMaxCredits());
    }

    public boolean dailyCheckinEnabled() { return booleanSetting("daily_checkin.enabled", true); }
    public int dailyCheckinCredits() { return intSetting("daily_checkin.credits", 2); }
    public int dailyCheckinMinCredits() { return intSetting("daily_checkin.min_credits", dailyCheckinCredits()); }
    public int dailyCheckinMaxCredits() { return Math.max(dailyCheckinMinCredits(), intSetting("daily_checkin.max_credits", dailyCheckinCredits())); }

    public Map<String, Object> dailyCheckinStatus(long userId) {
        LocalDate today = today();
        Integer claimed = jdbc.queryForObject("SELECT COUNT(*) FROM daily_checkins WHERE user_id=? AND checkin_date=?", Integer.class, userId, today);
        return Map.of("enabled", dailyCheckinEnabled(), "claimed", claimed != null && claimed > 0,
                "credits", dailyCheckinCredits(), "minCredits", dailyCheckinMinCredits(), "maxCredits", dailyCheckinMaxCredits(), "date", today.toString());
    }

    @Transactional
    public Map<String, Object> claimDailyCheckin(long userId) {
        if (!dailyCheckinEnabled()) throw new AuthException(403, "每日签到暂未开启");
        LocalDate today = today();
        try {
            jdbc.update("INSERT INTO daily_checkins (user_id, checkin_date) VALUES (?, ?)", userId, today);
        } catch (DuplicateKeyException e) {
            return dailyCheckinStatus(userId);
        }
        int reward = ThreadLocalRandom.current().nextInt(dailyCheckinMinCredits(), dailyCheckinMaxCredits() + 1);
        jdbc.update("UPDATE users SET credits=credits+?, updated_at=CURRENT_TIMESTAMP WHERE id=?", reward, userId);
        int balance = balance(userId);
        jdbc.update("INSERT INTO credit_transactions (user_id, amount, balance_after, kind, note) VALUES (?, ?, ?, 'DAILY_CHECKIN', ?)",
                userId, reward, balance, "每日签到奖励（" + today + "）");
        return Map.of("enabled", true, "claimed", true, "credits", reward, "date", today.toString(), "balance", balance, "granted", reward);
    }

    @Transactional
    public long spend(long userId, int amount, String taskType, String taskId, String note) {
        if (amount <= 0) throw new IllegalArgumentException("扣费额度无效");
        int updated = jdbc.update("UPDATE users SET credits=credits-?, updated_at=CURRENT_TIMESTAMP WHERE id=? AND credits >= ?",
                amount, userId, amount);
        if (updated == 0) throw new AuthException(402, "额度不足");
        int balance = balance(userId);
        jdbc.update("""
                INSERT INTO credit_transactions (user_id, amount, balance_after, kind, task_type, task_id, note)
                VALUES (?, ?, ?, 'SPEND', ?, ?, ?)
                """, userId, -amount, balance, taskType, taskId, note);
        return jdbc.queryForObject("SELECT MAX(id) FROM credit_transactions WHERE user_id=? AND task_id=? AND kind='SPEND'",
                Long.class, userId, taskId);
    }

    @Transactional
    public void refund(long transactionId, String reason) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM credit_transactions WHERE id=? AND kind='SPEND'", transactionId);
        if (rows.isEmpty()) return;
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_transactions WHERE related_transaction_id=? AND kind='REFUND'",
                Integer.class, transactionId);
        if (existing != null && existing > 0) return;
        Map<String, Object> spend = rows.get(0);
        long userId = ((Number) spend.get("user_id")).longValue();
        int amount = Math.abs(((Number) spend.get("amount")).intValue());
        jdbc.update("UPDATE users SET credits=credits+?, updated_at=CURRENT_TIMESTAMP WHERE id=?", amount, userId);
        jdbc.update("""
                INSERT INTO credit_transactions (user_id, amount, balance_after, kind, task_type, task_id, related_transaction_id, note)
                VALUES (?, ?, ?, 'REFUND', ?, ?, ?, ?)
                """, userId, amount, balance(userId), spend.get("task_type"), spend.get("task_id"), transactionId, reason);
    }

    public Long findSpendTransactionId(String taskId) {
        if (taskId == null || taskId.isBlank()) return null;
        List<Long> ids = jdbc.query("""
                SELECT id FROM credit_transactions
                WHERE task_id=? AND kind='SPEND'
                ORDER BY id DESC LIMIT 1
                """, (rs, rowNum) -> rs.getLong("id"), taskId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Transactional
    public void adjust(long userId, int amount, String note) {
        if (amount == 0) return;
        if (amount < 0) {
            int updated = jdbc.update("UPDATE users SET credits=credits+?, updated_at=CURRENT_TIMESTAMP WHERE id=? AND credits >= ?",
                    amount, userId, Math.abs(amount));
            if (updated == 0) throw new AuthException(402, "额度不足，无法扣减");
        } else {
            jdbc.update("UPDATE users SET credits=credits+?, updated_at=CURRENT_TIMESTAMP WHERE id=?", amount, userId);
        }
        jdbc.update("INSERT INTO credit_transactions (user_id, amount, balance_after, kind, note) VALUES (?, ?, ?, 'ADMIN_ADJUST', ?)",
                userId, amount, balance(userId), note == null ? "root 后台调整" : note);
    }

    public int balance(long userId) {
        Integer value = jdbc.queryForObject("SELECT credits FROM users WHERE id=?", Integer.class, userId);
        return value == null ? 0 : value;
    }

    public List<Map<String, Object>> users() {
        return jdbc.queryForList("SELECT id, username, role, credits, enabled, created_at FROM users ORDER BY id");
    }

    public List<Map<String, Object>> transactions() {
        return jdbc.queryForList("""
                SELECT t.*, u.username FROM credit_transactions t
                JOIN users u ON u.id=t.user_id
                ORDER BY t.id DESC LIMIT 100
                """);
    }

    public List<Map<String, Object>> dailyCheckinLeaderboard() {
        return jdbc.queryForList("""
                SELECT u.username, t.amount, t.created_at
                FROM credit_transactions t JOIN users u ON u.id=t.user_id
                WHERE t.kind='DAILY_CHECKIN' AND CAST(t.created_at AS DATE)=?
                ORDER BY t.amount DESC, t.created_at ASC LIMIT 10
                """, today());
    }

    public List<Map<String, Object>> invites() {
        return jdbc.queryForList("SELECT * FROM invite_codes ORDER BY id DESC LIMIT 100");
    }

    @Transactional
    public String createInvite(long rootId, String code, int credits, int maxUses) {
        return createInvite(rootId, code, credits, maxUses, null);
    }

    @Transactional
    public String createInvite(long rootId, String code, int credits, int maxUses, String expiresAt) {
        String clean = code == null || code.isBlank()
                ? java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : code.trim();
        java.sql.Timestamp expiry = parseExpiry(expiresAt);
        jdbc.update("INSERT INTO invite_codes (code, credits, max_uses, created_by, expires_at) VALUES (?, ?, ?, ?, ?)",
                clean, Math.max(0, credits), Math.max(1, maxUses), rootId, expiry);
        return clean;
    }

    @Transactional
    public void updateInvite(long id, boolean enabled, String expiresAt) {
        int updated = jdbc.update("UPDATE invite_codes SET enabled=?, expires_at=? WHERE id=?", enabled, parseExpiry(expiresAt), id);
        if (updated == 0) throw new AuthException(404, "邀请码不存在");
    }

    /** Remove only unused codes so registration and credit history stay auditable. */
    @Transactional
    public void deleteUnusedInvite(long id) {
        int deleted = jdbc.update("DELETE FROM invite_codes WHERE id=? AND used_count=0", id);
        if (deleted > 0) return;
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM invite_codes WHERE id=?", Integer.class, id);
        if (exists == null || exists == 0) throw new AuthException(404, "邀请码不存在");
        throw new AuthException(400, "已使用的邀请码不能删除，请改为撤销");
    }

    @Transactional
    public void updateUserStatus(long id, boolean enabled) {
        Map<String, Object> user = jdbc.queryForList("SELECT role FROM users WHERE id=?", id).stream().findFirst()
                .orElseThrow(() -> new AuthException(404, "用户不存在"));
        if ("ROOT".equals(String.valueOf(user.get("role"))) && !enabled) {
            throw new AuthException(400, "不能停用 root 账户");
        }
        jdbc.update("UPDATE users SET enabled=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", enabled, id);
        if (!enabled) jdbc.update("DELETE FROM user_sessions WHERE user_id=?", id);
    }

    public Map<String, Object> stats() {
        return Map.of(
                "users", count("SELECT COUNT(*) FROM users"),
                "activeUsers", count("SELECT COUNT(*) FROM users WHERE enabled=TRUE"),
                "activeInvites", count("SELECT COUNT(*) FROM invite_codes WHERE enabled=TRUE AND used_count < max_uses AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)"),
                "creditsIssued", sum("SELECT COALESCE(SUM(amount), 0) FROM credit_transactions WHERE amount > 0"),
                "creditsSpent", Math.abs(sum("SELECT COALESCE(SUM(amount), 0) FROM credit_transactions WHERE kind='SPEND'")));
    }

    public void updateSettings(int translationCreditPerPage, int pptCreditPerTask, boolean dailyCheckinEnabled, int dailyCheckinMinCredits, int dailyCheckinMaxCredits) {
        setSetting("translation.credit_per_page", String.valueOf(Math.max(1, translationCreditPerPage)));
        setSetting("ppt.credit_per_task", String.valueOf(Math.max(1, pptCreditPerTask)));
        setSetting("daily_checkin.enabled", String.valueOf(dailyCheckinEnabled));
        int min = Math.max(1, dailyCheckinMinCredits);
        int max = Math.max(min, dailyCheckinMaxCredits);
        setSetting("daily_checkin.credits", String.valueOf(min));
        setSetting("daily_checkin.min_credits", String.valueOf(min));
        setSetting("daily_checkin.max_credits", String.valueOf(max));
    }

    /** Compatibility for callers that still provide a fixed daily reward. */
    public void updateSettings(int translationCreditPerPage, int pptCreditPerTask, boolean dailyCheckinEnabled, int dailyCheckinCredits) {
        updateSettings(translationCreditPerPage, pptCreditPerTask, dailyCheckinEnabled, dailyCheckinCredits, dailyCheckinCredits);
    }

    private int intSetting(String key, int fallback) {
        List<String> values = jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?", String.class, key);
        if (values.isEmpty()) return fallback;
        try {
            return Math.max(1, Integer.parseInt(values.get(0)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean booleanSetting(String key, boolean fallback) {
        List<String> values = jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?", String.class, key);
        return values.isEmpty() ? fallback : Boolean.parseBoolean(values.get(0));
    }

    private LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }

    private void setSetting(String key, String value) {
        int updated = jdbc.update("UPDATE app_settings SET setting_value=?, updated_at=CURRENT_TIMESTAMP WHERE setting_key=?",
                value, key);
        if (updated == 0) {
            jdbc.update("INSERT INTO app_settings (setting_key, setting_value) VALUES (?, ?)", key, value);
        }
    }

    private void ensureSetting(String key, String value) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_settings WHERE setting_key=?", Integer.class, key);
        if (count == null || count == 0) {
            jdbc.update("INSERT INTO app_settings (setting_key, setting_value) VALUES (?, ?)", key, value);
        }
    }

    private java.sql.Timestamp parseExpiry(String value) {
        if (value == null || value.isBlank()) return null;
        try { return java.sql.Timestamp.from(java.time.Instant.parse(value)); }
        catch (Exception e) { throw new AuthException(400, "过期时间格式无效"); }
    }
    private int count(String sql) { Integer value = jdbc.queryForObject(sql, Integer.class); return value == null ? 0 : value; }
    private int sum(String sql) { Number value = jdbc.queryForObject(sql, Number.class); return value == null ? 0 : value.intValue(); }
}
