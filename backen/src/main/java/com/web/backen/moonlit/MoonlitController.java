package com.web.backen.moonlit;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/moonlit")
public class MoonlitController {
    private final MoonlitService service; private final AuthService auth;
    public MoonlitController(MoonlitService service, AuthService auth) { this.service=service; this.auth=auth; }
    @GetMapping("/state") public ResponseEntity<?> state(HttpServletRequest request) { try { return ResponseEntity.ok(ok(service.state(user(request).id()))); } catch (AuthException e) { return error(e); } }
    @PostMapping("/draw") public ResponseEntity<?> draw(@RequestBody Map<String,Object> body, HttpServletRequest request) { try { auth.requireCsrf(request); Object count = body.getOrDefault("count", 1); return ResponseEntity.ok(ok(service.draw(user(request).id(), String.valueOf(body.getOrDefault("method", "")), count instanceof Number n ? n.intValue() : 1))); } catch (AuthException e) { return error(e); } }
    @PutMapping("/display") public ResponseEntity<?> display(@RequestBody Map<String,Object> body, HttpServletRequest request) { try { auth.requireCsrf(request); service.setDisplay(user(request).id(), String.valueOf(body.getOrDefault("cardId", "")), ((Number) body.getOrDefault("theme", 0)).intValue()); return ResponseEntity.ok(ok(true)); } catch (AuthException e) { return error(e); } }
    private com.web.backen.auth.AuthUser user(HttpServletRequest request) { return auth.requireUser(request); }
    private Map<String,Object> ok(Object data) { return Map.of("code",200,"data",data); }
    private ResponseEntity<?> error(AuthException e) { return ResponseEntity.status(e.getStatus()).body(Map.of("code",e.getStatus(),"message",e.getMessage())); }
}
