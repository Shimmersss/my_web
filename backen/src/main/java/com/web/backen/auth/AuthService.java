package com.web.backen.auth;

import com.web.backen.config.AuthConfig;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AuthService {
    public static final String COOKIE_NAME = "rd_session";
    private static final String CSRF_HEADER = "X-CSRF-Token";

    private final JdbcTemplate jdbc;
    private final AuthConfig config;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(JdbcTemplate jdbc, AuthConfig config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    @PostConstruct
    public void bootstrapRoot() {
        Integer roots = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role='ROOT'", Integer.class);
        if (roots != null && roots > 0) return;
        if (config.getRootPassword() == null || config.getRootPassword().length() < 6) {
            throw new IllegalStateException("首次初始化 root 需要设置长度至少 6 位的 ROOT_PASSWORD");
        }
        String username = normalizeUsername(config.getRootUsername());
        jdbc.update("INSERT INTO users (username, password_hash, role, credits, enabled) VALUES (?, ?, 'ROOT', 0, TRUE)",
                username, passwordEncoder.encode(config.getRootPassword()));
    }

    @Transactional
    public AuthSession login(String username, String password) {
        AuthUser user = findByUsername(normalizeUsername(username))
                .orElseThrow(() -> new AuthException(401, "用户名或密码错误"));
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, user.id());
        if (!user.enabled() || hash == null || !passwordEncoder.matches(password == null ? "" : password, hash)) {
            throw new AuthException(401, "用户名或密码错误");
        }
        String token = randomToken();
        String csrf = randomToken();
        Instant expiresAt = Instant.now().plus(Duration.ofDays(Math.max(1, config.getSessionDays())));
        jdbc.update("INSERT INTO user_sessions (user_id, token_hash, csrf_token, expires_at) VALUES (?, ?, ?, ?)",
                user.id(), sha256(token), csrf, Timestamp.from(expiresAt));
        return new AuthSession(token, csrf, expiresAt, refreshUser(user.id()));
    }

    @Transactional
    public AuthUser register(String username, String password, String inviteCode) {
        String cleanUsername = normalizeUsername(username);
        validatePassword(password);
        String code = inviteCode == null ? "" : inviteCode.trim();
        Map<String, Object> invite = jdbc.queryForList(
                "SELECT * FROM invite_codes WHERE code=? AND enabled=TRUE AND used_count < max_uses AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)",
                code).stream().findFirst().orElseThrow(() -> new AuthException(400, "邀请码无效或已过期"));
        if (findByUsername(cleanUsername).isPresent()) {
            throw new AuthException(409, "用户名已存在");
        }
        int consumed = jdbc.update("""
                UPDATE invite_codes
                SET used_count=used_count+1
                WHERE id=? AND enabled=TRUE AND used_count < max_uses AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                """, invite.get("id"));
        if (consumed == 0) {
            throw new AuthException(400, "邀请码无效或已过期");
        }
        int credits = ((Number) invite.get("credits")).intValue();
        jdbc.update("INSERT INTO users (username, password_hash, role, credits, enabled) VALUES (?, ?, 'USER', ?, TRUE)",
                cleanUsername, passwordEncoder.encode(password), credits);
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, cleanUsername);
        if (credits > 0) {
            jdbc.update("INSERT INTO credit_transactions (user_id, amount, balance_after, kind, note) VALUES (?, ?, ?, 'INVITE_GRANT', ?)",
                    userId, credits, credits, "邀请码初始额度");
        }
        return refreshUser(userId);
    }

    public Optional<AuthUser> currentUser(HttpServletRequest request) {
        String token = cookieValue(request);
        if (token == null || token.isBlank()) return Optional.empty();
        List<AuthUser> users = jdbc.query("""
                SELECT u.id, u.username, u.role, u.credits, u.enabled
                FROM user_sessions s JOIN users u ON u.id=s.user_id
                WHERE s.token_hash=? AND s.expires_at > CURRENT_TIMESTAMP AND u.enabled=TRUE
                """, (rs, rowNum) -> new AuthUser(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("role"),
                rs.getInt("credits"),
                rs.getBoolean("enabled")), sha256(token));
        return users.stream().findFirst();
    }

    public Optional<String> currentCsrfToken(HttpServletRequest request) {
        String token = cookieValue(request);
        if (token == null || token.isBlank()) return Optional.empty();
        return jdbc.queryForList(
                "SELECT csrf_token FROM user_sessions WHERE token_hash=? AND expires_at > CURRENT_TIMESTAMP",
                String.class, sha256(token)).stream().findFirst();
    }

    public AuthUser requireUser(HttpServletRequest request) {
        return currentUser(request).orElseThrow(() -> new AuthException(401, "请先登录"));
    }

    public AuthUser requireRoot(HttpServletRequest request) {
        AuthUser user = requireUser(request);
        if (!user.isRoot()) throw new AuthException(403, "需要 root 权限");
        return user;
    }

    public void requireCsrf(HttpServletRequest request) {
        String token = cookieValue(request);
        String csrf = request.getHeader(CSRF_HEADER);
        if (token == null || csrf == null || csrf.isBlank()) {
            throw new AuthException(403, "缺少 CSRF token");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_sessions WHERE token_hash=? AND csrf_token=? AND expires_at > CURRENT_TIMESTAMP",
                Integer.class, sha256(token), csrf);
        if (count == null || count == 0) throw new AuthException(403, "CSRF token 无效");
    }

    public void logout(HttpServletRequest request) {
        String token = cookieValue(request);
        if (token != null) jdbc.update("DELETE FROM user_sessions WHERE token_hash=?", sha256(token));
    }

    public ResponseCookie sessionCookie(String token, Instant expiresAt) {
        return ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(config.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.between(Instant.now(), expiresAt))
                .build();
    }

    public ResponseCookie clearCookie() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(config.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    public AuthUser refreshUser(long id) {
        return jdbc.queryForObject("SELECT id, username, role, credits, enabled FROM users WHERE id=?",
                (rs, rowNum) -> new AuthUser(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("role"),
                        rs.getInt("credits"),
                        rs.getBoolean("enabled")), id);
    }

    private Optional<AuthUser> findByUsername(String username) {
        return jdbc.query("SELECT id, username, role, credits, enabled FROM users WHERE username=?",
                (rs, rowNum) -> new AuthUser(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("role"),
                        rs.getInt("credits"),
                        rs.getBoolean("enabled")), username).stream().findFirst();
    }

    private String normalizeUsername(String username) {
        String value = username == null ? "" : username.trim().toLowerCase();
        if (!value.matches("[a-z0-9_][a-z0-9_.-]{2,31}")) {
            throw new AuthException(400, "用户名需为 3-32 位字母、数字、点、下划线或短横线");
        }
        return value;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 10) {
            throw new AuthException(400, "密码至少 10 位");
        }
    }

    private String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record AuthSession(String token, String csrfToken, Instant expiresAt, AuthUser user) {}
}
