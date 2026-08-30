package com.web.backen.matchmaking;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import com.web.backen.auth.AuthUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/matchmaking/trial")
public class MatchmakingTrialController {
    private final MatchmakingTrialService trials;
    private final AuthService authService;

    public MatchmakingTrialController(MatchmakingTrialService trials, AuthService authService) {
        this.trials = trials;
        this.authService = authService;
    }

    @PostMapping("/redeem")
    public ResponseEntity<?> redeem(@RequestBody Map<String, Object> body) {
        try {
            MatchmakingTrialService.Redemption redemption = trials.redeem(value(body.get("code")));
            AuthService.AuthSession session = redemption.session();
            Map<String, Object> data = userData(session.user(), session.csrfToken(), redemption.access());
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE,
                            authService.sessionCookie(session.token(), session.expiresAt()).toString())
                    .body(Map.of("code", 200, "message", "success", "data", data));
        } catch (AuthException exception) {
            return ResponseEntity.status(exception.getStatus())
                    .body(Map.of("code", exception.getStatus(), "message", exception.getMessage()));
        }
    }

    private Map<String, Object> userData(AuthUser user, String csrfToken, Map<String, Object> access) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.id());
        data.put("username", "婚恋内测");
        data.put("role", user.role());
        data.put("credits", 0);
        data.put("root", false);
        data.put("matchmakingTrial", true);
        data.put("csrfToken", csrfToken);
        data.put("trialAccess", access);
        return data;
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }
}
