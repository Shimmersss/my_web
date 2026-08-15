package com.web.backen.guestbook;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import com.web.backen.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final GuestbookService service;
    private final AuthService auth;

    public NotificationController(GuestbookService service, AuthService auth) { this.service = service; this.auth = auth; }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "1") int page, HttpServletRequest request) {
        try { return ok(service.notifications(auth.requireUser(request), page)); } catch (AuthException e) { return error(e); }
    }

    @GetMapping("/unread-count")
    public ResponseEntity<?> unreadCount(HttpServletRequest request) {
        try { return ok(Map.of("count", service.unreadCount(auth.requireUser(request)))); } catch (AuthException e) { return error(e); }
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<?> read(@PathVariable long id, HttpServletRequest request) {
        try { AuthUser user = mutationUser(request); service.markRead(id, user); return ok(Map.of("unreadCount", service.unreadCount(user))); } catch (AuthException e) { return error(e); }
    }

    @PostMapping("/read-all")
    public ResponseEntity<?> readAll(HttpServletRequest request) {
        try { AuthUser user = mutationUser(request); service.markAllRead(user); return ok(Map.of("unreadCount", 0)); } catch (AuthException e) { return error(e); }
    }

    private AuthUser mutationUser(HttpServletRequest request) { auth.requireCsrf(request); return auth.requireUser(request); }
    private ResponseEntity<?> ok(Object data) { return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data)); }
    private ResponseEntity<?> error(AuthException e) { return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus(), "message", e.getMessage())); }
}
