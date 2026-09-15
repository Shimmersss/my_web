package com.web.backen.auth;

import com.web.backen.translate.LlmService;
import com.web.backen.zotero.ZoteroService;
import com.web.backen.zotero.ZoteroCache;
import com.web.backen.github.GithubRankingService;
import com.web.backen.ppt.PptGenerationService;
import com.web.backen.ppt.PptCodexRunner;
import com.web.backen.translate.TranslationService;
import com.web.backen.imagegen.ImageGenerationService;
import com.web.backen.matchmaking.MatchmakingTrialService;
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
    private final ZoteroCache zoteroCache;
    private final GithubRankingService githubRankingService;
    private final PptGenerationService pptGenerationService;
    private final TranslationService translationService;
    private final PptCodexRunner pptCodexRunner;
    private final ImageGenerationService imageGenerationService;
    private final MatchmakingTrialService matchmakingTrialService;

    public AdminAccountController(AuthService authService, QuotaService quotaService,
                                  RuntimeConfigService runtimeConfigService, LlmService llmService,
                                  ZoteroService zoteroService, ZoteroCache zoteroCache,
                                  GithubRankingService githubRankingService,
                                  PptGenerationService pptGenerationService, TranslationService translationService,
                                  PptCodexRunner pptCodexRunner, ImageGenerationService imageGenerationService,
                                  MatchmakingTrialService matchmakingTrialService) {
        this.authService = authService;
        this.quotaService = quotaService;
        this.runtimeConfigService = runtimeConfigService;
        this.llmService = llmService;
        this.zoteroService = zoteroService;
        this.zoteroCache = zoteroCache;
        this.githubRankingService = githubRankingService;
        this.pptGenerationService = pptGenerationService;
        this.translationService = translationService;
        this.pptCodexRunner = pptCodexRunner;
        this.imageGenerationService = imageGenerationService;
        this.matchmakingTrialService = matchmakingTrialService;
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
                            "matchmakingTrialCodes", matchmakingTrialService.codes(),
                            "settings", quotaService.settings(),
                            "apiSettings", publicApiSettings(),
                            "stats", quotaService.stats())));
        } catch (AuthException e) {
            return error(e);
        }
    }

    @PostMapping("/matchmaking-trial-codes")
    public ResponseEntity<?> createMatchmakingTrialCode(HttpServletRequest request,
                                                         @RequestBody Map<String, Object> body) {
        try {
            authService.requireCsrf(request);
            AuthUser root = authService.requireRoot(request);
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data",
                    matchmakingTrialService.createCode(root.id(), value(body.get("expiresAt")))));
        } catch (AuthException e) {
            return error(e);
        }
    }

    @PatchMapping("/matchmaking-trial-codes/{id}")
    public ResponseEntity<?> updateMatchmakingTrialCode(HttpServletRequest request, @PathVariable long id,
                                                         @RequestBody Map<String, Object> body) {
        try {
            authService.requireCsrf(request);
            authService.requireRoot(request);
            matchmakingTrialService.updateCode(id, booleanValue(body.get("enabled"), true),
                    value(body.get("expiresAt")));
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data",
                    matchmakingTrialService.codes()));
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
                    booleanValue(body.get("dailyCheckinEnabled"), true), intValue(body.get("dailyCheckinMinCredits"), intValue(body.get("dailyCheckinCredits"), 2)),
                    intValue(body.get("dailyCheckinMaxCredits"), intValue(body.get("dailyCheckinCredits"), 2)),
                    intValue(body.get("imageLowCredits"), quotaService.imageCredit("low")),
                    intValue(body.get("imageMediumCredits"), quotaService.imageCredit("medium")),
                    intValue(body.get("imageHighCredits"), quotaService.imageCredit("high")),
                    intValue(body.get("matchmakingCreditPerReport"), quotaService.matchmakingCreditPerReport()));
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
            zoteroCache.refreshAsync();
            pptGenerationService.cleanupHistory();
            translationService.cleanupHistory();
            imageGenerationService.cleanupHistory();
            return ResponseEntity.ok(Map.of("code", 200, "data", publicApiSettings(), "message", "success"));
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
                case "matchmaking" -> llmService.testConnection(
                        text(config, "baseUrl", runtimeConfigService.matchmakingLlmUrl()),
                        secret(config.get("apiKey"), runtimeConfigService.matchmakingLlmKey()),
                        text(config, "model", runtimeConfigService.matchmakingLlmModel()),
                        text(config, "protocol", runtimeConfigService.matchmakingLlmProtocol()));
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
                case "codexppt" -> pptCodexRunner.testConnection(
                        secret(config.get("apiKey"), runtimeConfigService.codexPptKey()),
                        text(config, "model", runtimeConfigService.codexPptModel()),
                        text(config, "reasoningEffort", runtimeConfigService.codexPptReasoningEffort()),
                        runtimeConfigService.normalizeCodexPptProviderBaseUrl(
                                text(config, "providerBaseUrl", runtimeConfigService.codexPptProviderBaseUrl())));
                case "imagegeneration" -> runtimeConfigService.testImageGenerationConnection(
                        text(config, "baseUrl", runtimeConfigService.imageGenerationEndpoint()),
                        secret(config.get("apiKey"), runtimeConfigService.imageGenerationKey()),
                        text(config, "model", runtimeConfigService.imageGenerationModel()));
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

    @PutMapping("/users/{id}/password")
    public ResponseEntity<?> resetUserPassword(HttpServletRequest request, @PathVariable long id,
                                               @RequestBody Map<String, Object> body) {
        try {
            authService.requireCsrf(request);
            authService.requireRoot(request);
            authService.resetPassword(id, value(body.get("password")));
            return ResponseEntity.ok(Map.of("code", 200, "message", "密码已重置，已退出该账户的所有登录会话"));
        } catch (AuthException e) { return error(e); }
    }

    private ResponseEntity<?> error(AuthException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus(), "message", e.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publicApiSettings() {
        Map<String, Object> data = new LinkedHashMap<>(runtimeConfigService.publicSettings());
        Object raw = data.get("codexPpt");
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> codex = new LinkedHashMap<>((Map<String, Object>) map);
            Map<String, Object> localCli = pptCodexRunner.localCliStatus();
            codex.put("localCli", localCli);
            codex.put("configured", Boolean.TRUE.equals(codex.get("configured"))
                    || Boolean.TRUE.equals(localCli.get("available")));
            data.put("codexPpt", codex);
        }
        return data;
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
