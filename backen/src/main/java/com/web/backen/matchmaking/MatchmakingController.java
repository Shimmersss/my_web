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
    private final MatchmakingService service; private final AuthService auth; private final RuntimeConfigService runtime; private final QuotaService quota; private final MatchmakingTrialService trials;
    public MatchmakingController(MatchmakingService service, AuthService auth, RuntimeConfigService runtime, QuotaService quota, MatchmakingTrialService trials) { this.service=service; this.auth=auth; this.runtime=runtime; this.quota=quota; this.trials=trials; }
    @GetMapping("/catalogue") public Map<String,Object> catalogue() { return ok(service.catalogue()); }
    @GetMapping("/status") public Map<String,Object> status(HttpServletRequest request) { return ok(service.status(access(request))); }
    @PostMapping("/tarot-draws") public ResponseEntity<?> draw(HttpServletRequest request) {
        try { AuthUser user=access(request); auth.requireCsrf(request); return ResponseEntity.ok(ok(service.drawTarot(user))); }
        catch(AuthException e) { return error(e); }
    }
    @GetMapping("/tarot-draws/current") public ResponseEntity<?> currentDraw(HttpServletRequest request) {
        try { return ResponseEntity.ok(ok(service.currentTarot(access(request)))); }
        catch(AuthException e) { return error(e); }
    }
    @PostMapping("/reports") public ResponseEntity<?> create(@RequestBody Map<String,Object> body, HttpServletRequest request) {
        try { AuthUser user=access(request); auth.requireCsrf(request); return ResponseEntity.ok(ok(service.createTask(user,body))); }
        catch(AuthException e) { return error(e); }
    }
    @GetMapping("/tasks") public Map<String,Object> tasks(HttpServletRequest request) { return ok(service.tasks(access(request))); }
    @GetMapping("/tasks/{taskId}") public ResponseEntity<?> task(@PathVariable String taskId, HttpServletRequest request) {
        try { return ResponseEntity.ok(ok(service.task(access(request), taskId))); }
        catch(AuthException e) { return error(e); }
    }
    @GetMapping("/reports") public Map<String,Object> reports(HttpServletRequest request) { return ok(service.reportSummaries(access(request))); }
    @GetMapping("/reports/latest") public Map<String,Object> latest(HttpServletRequest request) { return ok(service.latest(access(request))); }
    @GetMapping("/reports/{reportId}") public ResponseEntity<?> report(@PathVariable String reportId, HttpServletRequest request) {
        try { return ResponseEntity.ok(ok(service.report(access(request), reportId))); }
        catch(AuthException e) { return error(e); }
    }
    @DeleteMapping("/reports/{reportId}") public ResponseEntity<?> deleteReport(@PathVariable String reportId, HttpServletRequest request) {
        try { AuthUser user=access(request); auth.requireCsrf(request); return ResponseEntity.ok(ok(service.deleteReport(user, reportId))); }
        catch(AuthException e) { return error(e); }
    }
    @DeleteMapping("/data") public ResponseEntity<?> delete(HttpServletRequest request) {
        try { AuthUser user=access(request); auth.requireCsrf(request); if(user.isMatchmakingTrial()) throw new AuthException(403,"内测身份不能清空全部数据"); return ResponseEntity.ok(ok(service.deleteAll(user))); }
        catch(AuthException e) { return error(e); }
    }
    private AuthUser access(HttpServletRequest request) { AuthUser user=auth.requireSessionUser(request); if(user.isMatchmakingTrial()){ trials.requireEnabled(user.id()); return user; } if("ROOT".equalsIgnoreCase(runtime.visibilityLevel("Matchmaking"))&&!user.isRoot()) throw new AuthException(403,"无权访问该节目"); return user; }
    private Map<String,Object> ok(Object data) { return Map.of("code",200,"data",data); }
    private ResponseEntity<?> error(AuthException e) { return ResponseEntity.status(e.getStatus()).body(Map.of("code",e.getStatus(),"message",e.getMessage())); }
}
