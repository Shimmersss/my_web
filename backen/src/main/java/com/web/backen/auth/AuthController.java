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

    public AuthController(AuthService authService, QuotaService quotaService) {
        this.authService = authService;
        this.quotaService = quotaService;
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
        return ok(quotaService.settings());
    }

    private Map<String, Object> userData(AuthUser user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.id());
        data.put("username", user.username());
        data.put("role", user.role());
        data.put("credits", user.credits());
        data.put("root", user.isRoot());
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
