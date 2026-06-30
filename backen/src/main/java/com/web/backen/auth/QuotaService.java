package com.web.backen.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.Map;

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
                "pptCreditPerTask", pptCreditPerTask());
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

    public List<Map<String, Object>> invites() {
        return jdbc.queryForList("SELECT * FROM invite_codes ORDER BY id DESC LIMIT 100");
    }

    @Transactional
    public String createInvite(long rootId, String code, int credits, int maxUses) {
        String clean = code == null || code.isBlank()
                ? java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : code.trim();
        jdbc.update("INSERT INTO invite_codes (code, credits, max_uses, created_by) VALUES (?, ?, ?, ?)",
                clean, Math.max(0, credits), Math.max(1, maxUses), rootId);
        return clean;
    }

    public void updateSettings(int translationCreditPerPage, int pptCreditPerTask) {
        setSetting("translation.credit_per_page", String.valueOf(Math.max(1, translationCreditPerPage)));
        setSetting("ppt.credit_per_task", String.valueOf(Math.max(1, pptCreditPerTask)));
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
}
