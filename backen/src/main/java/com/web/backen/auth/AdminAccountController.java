package com.web.backen.auth;

import com.web.backen.translate.LlmService;
import com.web.backen.zotero.ZoteroService;
import com.web.backen.github.GithubRankingService;
import com.web.backen.ppt.PptGenerationService;
import com.web.backen.translate.TranslationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {
    private final AuthService authService;
    private final QuotaService quotaService;
    private final RuntimeConfigService runtimeConfigService;
    private final LlmService llmService;
    private final ZoteroService zoteroService;
    private final GithubRankingService githubRankingService;
    private final PptGenerationService pptGenerationService;
    private final TranslationService translationService;

    public AdminAccountController(AuthService authService, QuotaService quotaService,
                                  RuntimeConfigService runtimeConfigService, LlmService llmService,
                                  ZoteroService zoteroService, GithubRankingService githubRankingService,
                                  PptGenerationService pptGenerationService, TranslationService translationService) {
        this.authService = authService;
        this.quotaService = quotaService;
        this.runtimeConfigService = runtimeConfigService;
        this.llmService = llmService;
        this.zoteroService = zoteroService;
        this.githubRankingService = githubRankingService;
        this.pptGenerationService = pptGenerationService;
        this.translationService = translationService;
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
                            "settings", quotaService.settings(),
                            "apiSettings", runtimeConfigService.publicSettings(),
                            "stats", quotaService.stats())));
        } catch (AuthException e) {
            return error(e);
        }
    }

    @PostMapping("/invites")
    public ResponseEntity<?> createInvite(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        try {
            authService.requireCsrf(request);
            AuthUser root = authService.requireRoot(request);
            String code = quotaService.createInvite(root.id(), value(body.get("code")), intValue(body.get("credits"), 0), intValue(body.get("maxUses"), 1), value(body.get("expiresAt")));
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
            quotaService.updateSettings(intValue(body.get("translationCreditPerPage"), 1), intValue(body.get("pptCreditPerTask"), 10),
                    booleanValue(body.get("dailyCheckinEnabled"), true), intValue(body.get("dailyCheckinMinCredits"), intValue(body.get("dailyCheckinCredits"), 2)), intValue(body.get("dailyCheckinMaxCredits"), intValue(body.get("dailyCheckinCredits"), 2)));
            return ResponseEntity.ok(Map.of("code", 200, "data", quotaService.settings(), "message", "success"));
        } catch (AuthException e) {
            return error(e);
        }
    }

    @PutMapping("/api-settings")
    public ResponseEntity<?> apiSettings(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        try {
            authService.requireCsrf(request);
            authService.requireRoot(request);
            runtimeConfigService.update(body);
            pptGenerationService.cleanupHistory();
            translationService.cleanupHistory();
            return ResponseEntity.ok(Map.of("code", 200, "data", runtimeConfigService.publicSettings(), "message", "success"));
        } catch (AuthException e) { return error(e); }
    }

    @PostMapping("/github-ranking/refresh")
    public ResponseEntity<?> refreshGithubRanking(HttpServletRequest request) {
        try {
            authService.requireCsrf(request);
            authService.requireRoot(request);
            GithubRankingService.ManualRefreshResult result = githubRankingService.requestManualRefresh();
            return ResponseEntity.ok(Map.of("code", 200, "data", result, "message", result.message()));
        } catch (AuthException e) {
            return error(e);
        }
    }

    @PostMapping("/api-settings/test")
    public ResponseEntity<?> testApiSettings(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        try {
            authService.requireCsrf(request);
            authService.requireRoot(request);
            String provider = value(body.get("provider")).toLowerCase();
            Map<String, Object> config = map(body.get("config"));
            long started = System.nanoTime();
            Map<String, Object> result = switch (provider) {
                case "llm" -> llmService.testConnection(
                        text(config, "baseUrl", runtimeConfigService.llmUrl()),
                        secret(config.get("apiKey"), runtimeConfigService.llmKey()),
                        text(config, "model", runtimeConfigService.llmModel()),
                        text(config, "protocol", runtimeConfigService.llmProtocol()));
                case "babeldoc" -> llmService.testConnection(
                        text(config, "baseUrl", runtimeConfigService.babelUrl()),
                        secret(config.get("apiKey"), runtimeConfigService.babelKey()),
                        text(config, "model", runtimeConfigService.babelModel()), "openai");
                case "zotero" -> zoteroService.testConnection(
                        text(config, "baseUrl", runtimeConfigService.zoteroUrl()),
                        text(config, "userId", runtimeConfigService.zoteroUser()),
                        secret(config.get("apiKey"), runtimeConfigService.zoteroKey()));
                case "research" -> runtimeConfigService.testTavilyConnection(
                        text(config, "baseUrl", runtimeConfigService.tavilyUrl()),
                        secret(config.get("apiKey"), runtimeConfigService.tavilyKey()));
                case "mimosearch" -> runtimeConfigService.testMimoSearchConnection(
                        text(config, "baseUrl", runtimeConfigService.mimoSearchEndpoint()),
                        secret(config.get("apiKey"), runtimeConfigService.mimoSearchKey()),
                        text(config, "model", runtimeConfigService.mimoSearchModel()));
                default -> throw new AuthException(400, "不支持的 API 提供方");
            };
            Map<String, Object> data = new LinkedHashMap<>(result);
            data.put("latencyMs", Math.max(1, (System.nanoTime() - started) / 1_000_000));
            return ResponseEntity.ok(Map.of("code", 200, "data", data, "message", "success"));
        } catch (AuthException e) {
            return error(e);
        } catch (Exception e) {
            String message = e.getMessage() == null || e.getMessage().isBlank() ? "上游接口测试失败" : e.getMessage();
            return ResponseEntity.status(502).body(Map.of("code", 502, "message", message));
        }
    }

    @PatchMapping("/invites/{id}")
    public ResponseEntity<?> inviteStatus(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            authService.requireCsrf(request); authService.requireRoot(request);
            quotaService.updateInvite(id, Boolean.parseBoolean(value(body.get("enabled"))), value(body.get("expiresAt")));
            return ResponseEntity.ok(Map.of("code", 200, "data", quotaService.invites(), "message", "success"));
        } catch (AuthException e) { return error(e); }
    }

    @DeleteMapping("/invites/{id}")
    public ResponseEntity<?> deleteInvite(HttpServletRequest request, @PathVariable long id) {
        try {
            authService.requireCsrf(request); authService.requireRoot(request);
            quotaService.deleteUnusedInvite(id);
            return ResponseEntity.ok(Map.of("code", 200, "data", quotaService.invites(), "message", "success"));
        } catch (AuthException e) { return error(e); }
    }

    @PatchMapping("/users/{id}")
    public ResponseEntity<?> userStatus(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            authService.requireCsrf(request); authService.requireRoot(request);
            quotaService.updateUserStatus(id, Boolean.parseBoolean(value(body.get("enabled"))));
            return ResponseEntity.ok(Map.of("code", 200, "data", quotaService.users(), "message", "success"));
        } catch (AuthException e) { return error(e); }
    }

    private ResponseEntity<?> error(AuthException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus(), "message", e.getMessage()));
    }

    private String value(Object value) { return value == null ? "" : value.toString().trim(); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of(); }
    private String text(Map<String, Object> body, String key, String fallback) {
        String value = value(body.get(key));
        return value.isBlank() ? fallback : value;
    }
    private String secret(Object submitted, String fallback) {
        String value = value(submitted);
        return value.isBlank() || value.startsWith("已配置（") ? fallback : value;
    }
    private int intValue(Object value, int fallback) {
        try { return Integer.parseInt(value(value)); } catch (Exception e) { return fallback; }
    }
    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) return fallback;
        String raw = value(value);
        return raw.isBlank() ? fallback : Boolean.parseBoolean(raw);
    }
    private long longValue(Object value) {
        try { return Long.parseLong(value(value)); } catch (Exception e) { throw new AuthException(400, "用户 ID 无效"); }
    }
}
