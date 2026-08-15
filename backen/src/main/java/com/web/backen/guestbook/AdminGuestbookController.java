package com.web.backen.guestbook;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import com.web.backen.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/guestbook")
public class AdminGuestbookController {
    private final GuestbookService service;
    private final AuthService auth;

    public AdminGuestbookController(GuestbookService service, AuthService auth) { this.service = service; this.auth = auth; }

    @GetMapping("/entries")
    public ResponseEntity<?> list(@RequestParam(defaultValue = "all") String type, @RequestParam(defaultValue = "1") int page, HttpServletRequest request) {
        try { return ok(service.adminEntries(type, page, auth.requireRoot(request))); } catch (AuthException e) { return error(e); }
    }

    @DeleteMapping("/entries/{id}")
    public ResponseEntity<?> delete(@PathVariable long id, HttpServletRequest request) {
        try { auth.requireCsrf(request); AuthUser root = auth.requireRoot(request); service.delete(id, root); return ok(Map.of("deleted", true)); } catch (AuthException e) { return error(e); }
    }

    private ResponseEntity<?> ok(Object data) { return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data)); }
    private ResponseEntity<?> error(AuthException e) { return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus(), "message", e.getMessage())); }
}
