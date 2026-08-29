package com.web.backen.matchmaking;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.RuntimeConfigService;
import com.web.backen.auth.QuotaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/matchmaking")
public class MatchmakingController {
    private final MatchmakingService service; private final AuthService auth; private final RuntimeConfigService runtime; private final QuotaService quota;
    public MatchmakingController(MatchmakingService service, AuthService auth, RuntimeConfigService runtime, QuotaService quota) { this.service=service; this.auth=auth; this.runtime=runtime; this.quota=quota; }
    @GetMapping("/catalogue") public Map<String,Object> catalogue() { return ok(service.catalogue()); }
    @GetMapping("/status") public Map<String,Object> status(HttpServletRequest request) { return ok(service.status(access(request))); }
    @GetMapping("/reports/latest") public Map<String,Object> latest(HttpServletRequest request) { return ok(service.latest(access(request))); }
    @PostMapping("/reports") public ResponseEntity<?> create(@RequestBody Map<String,Object> body, HttpServletRequest request) {
        try { AuthUser user=access(request); auth.requireCsrf(request); Map<String,Object> report=new java.util.LinkedHashMap<>(service.create(user,body)); report.put("credits", quota.balance(user.id())); return ResponseEntity.ok(ok(report)); }
        catch(AuthException e) { return error(e); }
    }
    @DeleteMapping("/data") public ResponseEntity<?> delete(HttpServletRequest request) {
        try { AuthUser user=access(request); auth.requireCsrf(request); return ResponseEntity.ok(ok(service.deleteAll(user))); }
        catch(AuthException e) { return error(e); }
    }
    private AuthUser access(HttpServletRequest request) { AuthUser user=auth.requireUser(request); if("ROOT".equalsIgnoreCase(runtime.visibilityLevel("Matchmaking"))&&!user.isRoot()) throw new AuthException(403,"无权访问该节目"); return user; }
    private Map<String,Object> ok(Object data) { return Map.of("code",200,"data",data); }
    private ResponseEntity<?> error(AuthException e) { return ResponseEntity.status(e.getStatus()).body(Map.of("code",e.getStatus(),"message",e.getMessage())); }
}
