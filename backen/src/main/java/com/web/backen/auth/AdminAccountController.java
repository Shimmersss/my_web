package com.web.backen.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {
    private final AuthService authService;
    private final QuotaService quotaService;

    public AdminAccountController(AuthService authService, QuotaService quotaService) {
        this.authService = authService;
        this.quotaService = quotaService;
    }

    @GetMapping
    public ResponseEntity<?> dashboard(HttpServletRequest request) {
        try {
            authService.requireRoot(request);
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", Map.of(
                            "users", quotaService.users(),
                            "invites", quotaService.invites(),
                            "transactions", quotaService.transactions(),
                            "settings", quotaService.settings())));
        } catch (AuthException e) {
            return error(e);
        }
    }

    @PostMapping("/invites")
    public ResponseEntity<?> createInvite(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        try {
            authService.requireCsrf(request);
            AuthUser root = authService.requireRoot(request);
            String code = quotaService.createInvite(root.id(), value(body.get("code")), intValue(body.get("credits"), 0), intValue(body.get("maxUses"), 1));
            return ResponseEntity.ok(Map.of("code", 200, "data", Map.of("code", code), "message", "success"));
        } catch (AuthException e) {
            return error(e);
        }
    }

    @PostMapping("/credits")
    public ResponseEntity<?> adjustCredits(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        try {
            authService.requireCsrf(request);
            authService.requireRoot(request);
            quotaService.adjust(longValue(body.get("userId")), intValue(body.get("amount"), 0), value(body.get("note")));
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", ""));
        } catch (AuthException e) {
            return error(e);
        }
    }

    @PutMapping("/settings")
    public ResponseEntity<?> settings(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        try {
            authService.requireCsrf(request);
            authService.requireRoot(request);
            quotaService.updateSettings(intValue(body.get("translationCreditPerPage"), 1), intValue(body.get("pptCreditPerTask"), 10));
            return ResponseEntity.ok(Map.of("code", 200, "data", quotaService.settings(), "message", "success"));
        } catch (AuthException e) {
            return error(e);
        }
    }

    private ResponseEntity<?> error(AuthException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus(), "message", e.getMessage()));
    }

    private String value(Object value) { return value == null ? "" : value.toString().trim(); }
    private int intValue(Object value, int fallback) {
        try { return Integer.parseInt(value(value)); } catch (Exception e) { return fallback; }
    }
    private long longValue(Object value) {
        try { return Long.parseLong(value(value)); } catch (Exception e) { throw new AuthException(400, "用户 ID 无效"); }
    }
}
