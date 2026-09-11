package com.web.backen.guestbook;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import com.web.backen.auth.AuthUser;
import com.web.backen.settings.RuntimeConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/guestbook")
public class GuestbookController {
    private final GuestbookService service;
    private final AuthService auth;
    private final RuntimeConfigService runtime;

    public GuestbookController(GuestbookService service, AuthService auth, RuntimeConfigService runtime) {
        this.service = service;
        this.auth = auth;
        this.runtime = runtime;
    }

    @GetMapping("/messages")
    public ResponseEntity<?> messages(@RequestParam(defaultValue = "1") int page, HttpServletRequest request) {
        try { requireVisible(request); return ok(service.messages(page, auth.currentUser(request).orElse(null))); }
        catch (AuthException e) { return error(e); }
    }

    @GetMapping("/messages/{id}/replies")
    public ResponseEntity<?> replies(@PathVariable long id, @RequestParam(defaultValue = "1") int page, HttpServletRequest request) {
        try { requireVisible(request); return ok(service.replies(id, page, auth.currentUser(request).orElse(null))); }
        catch (AuthException e) { return error(e); }
    }

    @GetMapping("/entries/{id}/context")
    public ResponseEntity<?> context(@PathVariable long id, HttpServletRequest request) {
        try { requireVisible(request); return ok(service.context(id, auth.currentUser(request).orElse(null))); }
        catch (AuthException e) { return error(e); }
    }

    @PostMapping("/messages")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try { AuthUser user = mutationUser(request); return ok(service.createMessage(user, text(body))); }
        catch (AuthException e) { return error(e); }
    }

    @PostMapping("/messages/{id}/replies")
    public ResponseEntity<?> reply(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try { AuthUser user = mutationUser(request); return ok(service.createReply(user, id, text(body))); }
        catch (AuthException e) { return error(e); }
    }

    @PutMapping("/entries/{id}/like")
    public ResponseEntity<?> like(@PathVariable long id, HttpServletRequest request) {
        try { return ok(service.like(id, mutationUser(request))); }
        catch (AuthException e) { return error(e); }
    }

    @DeleteMapping("/entries/{id}/like")
    public ResponseEntity<?> unlike(@PathVariable long id, HttpServletRequest request) {
        try { return ok(service.unlike(id, mutationUser(request))); }
        catch (AuthException e) { return error(e); }
    }

    @DeleteMapping("/entries/{id}")
    public ResponseEntity<?> delete(@PathVariable long id, HttpServletRequest request) {
        try { service.delete(id, mutationUser(request)); return ok(Map.of("deleted", true)); }
        catch (AuthException e) { return error(e); }
    }

    private AuthUser mutationUser(HttpServletRequest request) {
        requireVisible(request);
        auth.requireCsrf(request);
        return auth.requireUser(request);
    }

    private void requireVisible(HttpServletRequest request) {
        String level = runtime.visibilityLevel("Guestbook");
        if ("ROOT".equalsIgnoreCase(level)) auth.requireRoot(request);
        else if ("USER".equalsIgnoreCase(level)) auth.requireUser(request);
    }

    private String text(Map<String, Object> body) { return body.get("content") == null ? "" : String.valueOf(body.get("content")); }
    private ResponseEntity<?> ok(Object data) { return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data)); }
    private ResponseEntity<?> error(AuthException e) { return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus(), "message", e.getMessage())); }
}
