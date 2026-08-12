package com.web.backen.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final QuotaService quotaService;
    private final RuntimeConfigService runtimeConfigService;

    public AuthController(AuthService authService, QuotaService quotaService, RuntimeConfigService runtimeConfigService) {
        this.authService = authService;
        this.quotaService = quotaService;
        this.runtimeConfigService = runtimeConfigService;
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        return ok(authService.currentUser(request).map(user -> {
            Map<String, Object> data = userData(user);
            authService.currentCsrfToken(request).ifPresent(csrf -> data.put("csrfToken", csrf));
            return data;
        }).orElse(null));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body) {
        try {
            AuthService.AuthSession session = authService.login(value(body.get("username")), value(body.get("password")));
            Map<String, Object> data = userData(session.user());
            data.put("csrfToken", session.csrfToken());
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, authService.sessionCookie(session.token(), session.expiresAt()).toString())
                    .body(okBody(data));
        } catch (AuthException e) {
            return error(e);
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, Object> body) {
        try {
            AuthUser user = authService.register(value(body.get("username")), value(body.get("password")), value(body.get("inviteCode")));
            return ResponseEntity.ok(okBody(userData(user)));
        } catch (AuthException e) {
            return error(e);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        authService.logout(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authService.clearCookie().toString())
                .body(okBody(null));
    }

    @GetMapping("/quota-settings")
    public Map<String, Object> quotaSettings() {
        Map<String, Object> data = new LinkedHashMap<>(quotaService.settings());
        data.put("pptImageGenerationQuality", runtimeConfigService.imageGenerationQuality());
        data.put("pptImageGenerationMaxImages", runtimeConfigService.imageGenerationMaxImages());
        return ok(data);
    }

    @PostMapping("/daily-checkin")
    public ResponseEntity<?> dailyCheckin(HttpServletRequest request) {
        try {
            authService.requireCsrf(request);
            AuthUser user = authService.requireUser(request);
            return ResponseEntity.ok(okBody(quotaService.claimDailyCheckin(user.id())));
        } catch (AuthException e) {
            return error(e);
        }
    }

    @GetMapping("/daily-checkin/leaderboard")
    public Map<String, Object> dailyCheckinLeaderboard() { return ok(quotaService.dailyCheckinLeaderboard()); }

    @GetMapping("/site-settings")
    public Map<String, Object> siteSettings() {
        return Map.of("code", 200, "message", "success", "data", Map.of("visibility", runtimeConfigService.publicSettings().get("visibility")));
    }

    private Map<String, Object> userData(AuthUser user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.id());
        data.put("username", user.username());
        data.put("role", user.role());
        data.put("credits", user.credits());
        data.put("root", user.isRoot());
        data.put("dailyCheckin", quotaService.dailyCheckinStatus(user.id()));
        return data;
    }

    private Map<String, Object> ok(Object data) {
        return okBody(data);
    }

    private Map<String, Object> okBody(Object data) {
        return Map.of("code", 200, "message", "success", "data", data == null ? "" : data);
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private ResponseEntity<?> error(AuthException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus(), "message", e.getMessage()));
    }
}
