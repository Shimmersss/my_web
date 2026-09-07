package com.web.backen.matchmaking;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class MatchmakingTrialService {
    public static final String STATUS_UNUSED = "UNUSED";
    public static final String STATUS_CLAIMED = "CLAIMED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_RETRYABLE = "RETRYABLE";
    public static final String STATUS_COMPLETED = "COMPLETED";

    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final Duration DEFAULT_VALIDITY = Duration.ofDays(7);

    private final JdbcTemplate jdbc;
    private final AuthService authService;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public MatchmakingTrialService(JdbcTemplate jdbc, AuthService authService) {
        this(jdbc, authService, Clock.systemUTC(), new SecureRandom());
    }

    MatchmakingTrialService(JdbcTemplate jdbc, Clock clock, SecureRandom random) {
        this(jdbc, null, clock, random);
    }

    MatchmakingTrialService(JdbcTemplate jdbc, AuthService authService, Clock clock, SecureRandom random) {
        this.jdbc = jdbc;
        this.authService = authService;
        this.clock = clock;
        this.random = random;
    }

    @Transactional
    public Map<String, Object> createCode(long rootId, String expiresAt) {
        String code = generateCode();
        Timestamp expiry = parseExpiry(expiresAt);
        jdbc.update("""
                INSERT INTO matchmaking_trial_codes
                    (code_hash, code_plain, code_suffix, status, enabled, expires_at, created_by)
                VALUES (?, ?, ?, ?, TRUE, ?, ?)
                """, hash(code), code, code.substring(code.length() - 4), STATUS_UNUSED, expiry, rootId);

        Map<String, Object> created = new LinkedHashMap<>();
        created.put("code", code);
        created.put("codeSuffix", code.substring(code.length() - 4));
        created.put("status", STATUS_UNUSED);
        created.put("enabled", true);
        created.put("expiresAt", expiry);
        return created;
    }

    public List<Map<String, Object>> codes() {
        return jdbc.query("""
                SELECT id, code_plain, code_suffix, guest_user_id, status, active_task_id, report_id,
                       enabled, expires_at, redeemed_at, completed_at, created_by, created_at, updated_at
                FROM matchmaking_trial_codes
                ORDER BY id DESC
                """, (rs, rowNum) -> adminSummary(rs));
    }

    @Transactional
    public void updateCode(long id, boolean enabled, String expiresAt) {
        Timestamp expiry = parseExpiry(expiresAt);
        int updated = jdbc.update("""
                UPDATE matchmaking_trial_codes
                SET enabled=?, expires_at=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, enabled, expiry, id);
        if (updated == 0) throw new AuthException(404, "内测邀请码不存在");
    }

    public Map<String, Object> redeemRecord(String rawCode) {
        List<Map<String, Object>> records = jdbc.query("""
                SELECT id, code_suffix, guest_user_id, status, active_task_id, report_id,
                       enabled, expires_at, redeemed_at, completed_at, created_by, created_at, updated_at
                FROM matchmaking_trial_codes
                WHERE code_hash=?
                """, (rs, rowNum) -> summary(rs), hash(rawCode));
        if (records.isEmpty()) throw invalidCode();
        Map<String, Object> record = records.get(0);
        Timestamp expiresAt = (Timestamp) record.get("expiresAt");
        boolean firstRedemptionExpired = record.get("guestUserId") == null
                && !clock.instant().isBefore(expiresAt.toInstant());
        if (!Boolean.TRUE.equals(record.get("enabled")) || firstRedemptionExpired) throw invalidCode();
        return record;
    }

    public Map<String, Object> accessForUser(long userId) {
        List<Map<String, Object>> records = jdbc.query("""
                SELECT id, code_suffix, guest_user_id, status, active_task_id, report_id,
                       enabled, expires_at, redeemed_at, completed_at, created_by, created_at, updated_at
                FROM matchmaking_trial_codes
                WHERE guest_user_id=?
                """, (rs, rowNum) -> summary(rs), userId);
        if (records.isEmpty()) throw invalidCode();
        Map<String, Object> record = records.get(0);
        if (!Boolean.TRUE.equals(record.get("enabled"))) throw invalidCode();
        String status = String.valueOf(record.get("status"));
        String reportId = record.get("reportId") == null ? "" : String.valueOf(record.get("reportId"));
        Map<String, Object> access = new LinkedHashMap<>(record);
        access.put("reportId", reportId);
        access.put("canGenerate", STATUS_CLAIMED.equals(status) || STATUS_RETRYABLE.equals(status));
        access.put("reportAvailable", !reportId.isBlank() && reportExists(reportId));
        return access;
    }

    @Transactional
    public Redemption redeem(String rawCode) {
        if (authService == null) throw new IllegalStateException("AuthService is required for trial redemption");
        List<Map<String, Object>> records = jdbc.query("""
                SELECT id, code_suffix, guest_user_id, status, active_task_id, report_id,
                       enabled, expires_at, redeemed_at, completed_at, created_by, created_at, updated_at
                FROM matchmaking_trial_codes
                WHERE code_hash=?
                FOR UPDATE
                """, (rs, rowNum) -> summary(rs), hash(rawCode));
        if (records.isEmpty()) throw invalidCode();
        Map<String, Object> record = records.get(0);
        if (!Boolean.TRUE.equals(record.get("enabled"))) throw invalidCode();

        Long userId = (Long) record.get("guestUserId");
        if (userId == null) {
            Timestamp expiresAt = (Timestamp) record.get("expiresAt");
            if (!clock.instant().isBefore(expiresAt.toInstant())) throw invalidCode();
            userId = createTrialUser().id();
            int bound = jdbc.update("""
                    UPDATE matchmaking_trial_codes
                    SET guest_user_id=?, status=?, redeemed_at=?, updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND guest_user_id IS NULL AND enabled=TRUE
                    """, userId, STATUS_CLAIMED, Timestamp.from(clock.instant()), record.get("id"));
            if (bound == 0) throw invalidCode();
        }

        AuthService.AuthSession session = authService.createSessionForUser(userId);
        return new Redemption(session, accessForUser(userId));
    }

    public Map<String, Object> requireEnabled(long userId) {
        return accessForUser(userId);
    }

    @Transactional
    public void reserveTask(long userId, String taskId) {
        int updated = jdbc.update("""
                UPDATE matchmaking_trial_codes
                SET status=?, active_task_id=?, updated_at=CURRENT_TIMESTAMP
                WHERE guest_user_id=? AND enabled=TRUE AND status IN (?, ?)
                """, STATUS_RUNNING, taskId, userId, STATUS_CLAIMED, STATUS_RETRYABLE);
        if (updated != 1) throw new AuthException(409, "该内测邀请码已在生成或已完成报告");
    }

    @Transactional
    public void markRetryable(long userId, String taskId) {
        jdbc.update("""
                UPDATE matchmaking_trial_codes
                SET status=?, active_task_id=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE guest_user_id=? AND enabled=TRUE AND status=? AND active_task_id=?
                """, STATUS_RETRYABLE, userId, STATUS_RUNNING, taskId);
    }

    @Transactional
    public void markCompleted(long userId, String taskId, String reportId) {
        int updated = jdbc.update("""
                UPDATE matchmaking_trial_codes
                SET status=?, active_task_id=NULL, report_id=?, completed_at=?, updated_at=CURRENT_TIMESTAMP
                WHERE guest_user_id=? AND enabled=TRUE AND status=? AND active_task_id=?
                """, STATUS_COMPLETED, reportId, Timestamp.from(clock.instant()), userId, STATUS_RUNNING, taskId);
        if (updated != 1) throw new AuthException(409, "内测邀请码状态已变化，无法保存报告");
    }

    private Map<String, Object> summary(ResultSet rs) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", rs.getLong("id"));
        result.put("codeSuffix", rs.getString("code_suffix"));
        result.put("guestUserId", nullableLong(rs, "guest_user_id"));
        result.put("status", rs.getString("status"));
        result.put("activeTaskId", rs.getString("active_task_id"));
        result.put("reportId", rs.getString("report_id"));
        result.put("enabled", rs.getBoolean("enabled"));
        result.put("expiresAt", rs.getTimestamp("expires_at"));
        result.put("redeemedAt", rs.getTimestamp("redeemed_at"));
        result.put("completedAt", rs.getTimestamp("completed_at"));
        result.put("createdBy", rs.getLong("created_by"));
        result.put("createdAt", rs.getTimestamp("created_at"));
        result.put("updatedAt", rs.getTimestamp("updated_at"));
        return result;
    }

    private Map<String, Object> adminSummary(ResultSet rs) throws SQLException {
        Map<String, Object> result = summary(rs);
        String code = rs.getString("code_plain");
        result.put("code", code);
        result.put("codeRecoverable", code != null && !code.isBlank());
        return result;
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private boolean reportExists(String reportId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_reports WHERE id=?", Integer.class, reportId);
        return count != null && count > 0;
    }

    private String generateCode() {
        StringBuilder code = new StringBuilder("MM-");
        for (int i = 0; i < 24; i++) code.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        return code.toString();
    }

    private com.web.backen.auth.AuthUser createTrialUser() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder username = new StringBuilder("trial_");
            for (int i = 0; i < 12; i++) username.append((char) ('a' + random.nextInt(26)));
            try {
                return authService.createInternalTrialUser(username.toString());
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // Try a new opaque username if the random value collides.
            }
        }
        throw new IllegalStateException("Unable to allocate trial user");
    }

    private Timestamp parseExpiry(String value) {
        if (value == null || value.isBlank()) return Timestamp.from(clock.instant().plus(DEFAULT_VALIDITY));
        try {
            return Timestamp.from(Instant.parse(value.trim()));
        } catch (Exception e) {
            throw new AuthException(400, "过期时间格式无效");
        }
    }

    private String hash(String rawCode) {
        String normalized = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private AuthException invalidCode() {
        return new AuthException(400, "内测邀请码无效、已过期或已撤销");
    }

    public record Redemption(AuthService.AuthSession session, Map<String, Object> access) {}
}
