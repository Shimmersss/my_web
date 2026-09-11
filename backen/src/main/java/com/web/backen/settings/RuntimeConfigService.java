package com.web.backen.settings;

import com.web.backen.auth.AuthException;

import com.web.backen.config.BabelDocConfig;
import com.web.backen.config.LlmConfig;
import com.web.backen.config.ImageGenerationConfig;
import com.web.backen.config.PptGenerationConfig;
import com.web.backen.config.TranslationConfig;
import com.web.backen.config.ZoteroConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 后台可维护的运行时配置。密钥只在后端内存和数据库中保存，接口永不返回明文。 */
@Service
public class RuntimeConfigService {
    private static final String LLM_URL = "api.llm.url";
    private static final String LLM_KEY = "api.llm.key";
    private static final String LLM_MODEL = "api.llm.model";
    private static final String LLM_PROTOCOL = "api.llm.protocol";
    private static final String MATCHMAKING_LLM_URL = "matchmaking.llm.url";
    private static final String MATCHMAKING_LLM_KEY = "matchmaking.llm.key";
    private static final String MATCHMAKING_LLM_MODEL = "matchmaking.llm.model";
    private static final String MATCHMAKING_LLM_PROTOCOL = "matchmaking.llm.protocol";
    private static final String BABEL_URL = "api.babeldoc.url";
    private static final String BABEL_KEY = "api.babeldoc.key";
    private static final String BABEL_MODEL = "api.babeldoc.model";
    private static final String ZOTERO_URL = "api.zotero.url";
    private static final String ZOTERO_KEY = "api.zotero.key";
    private static final String ZOTERO_USER = "api.zotero.user";
    private static final String TAVILY_URL = "api.tavily.url";
    private static final String TAVILY_KEY = "api.tavily.key";
    private static final String TAVILY_MAX_SEARCHES = "api.tavily.max-searches";
    private static final String SEMANTIC_SCHOLAR_KEY = "api.semantic-scholar.key";
    private static final String CODEX_PPT_KEY = "ppt.codex.api-key";
    private static final String CODEX_PPT_MODEL = "ppt.codex.model";
    private static final String CODEX_PPT_REASONING = "ppt.codex.reasoning-effort";
    private static final String CODEX_PPT_PROVIDER_BASE_URL = "ppt.codex.provider-base-url";
    private static final String IMAGE_GENERATION_URL = "ppt.image-generation.url";
    private static final String IMAGE_GENERATION_KEY = "ppt.image-generation.key";
    private static final String IMAGE_GENERATION_MODEL = "ppt.image-generation.model";
    private static final String IMAGE_GENERATION_QUALITY = "ppt.image-generation.quality";
    private static final String IMAGE_GENERATION_MAX_IMAGES = "ppt.image-generation.max-images";
    private static final Set<String> CODEX_MODELS = Set.of("gpt-5.4", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna");
    private static final Set<String> CODEX_REASONING = Set.of("low", "medium", "high", "xhigh", "max", "ultra");
    private static final String PPT_MAX_HISTORY = "ppt.history.max-per-user";
    private static final String PPT_MAX_GLOBAL_HISTORY = "ppt.history.max-total";
    private static final String TRANSLATION_MAX_HISTORY = "translation.history.max-per-user";
    private static final String TRANSLATION_MAX_GLOBAL_HISTORY = "translation.history.max-total";
    private static final String IMAGE_MAX_HISTORY = "image.history.max-per-user";
    private static final String IMAGE_MAX_GLOBAL_HISTORY = "image.history.max-total";
    private static final String PRESENTATION_IMAGE_MAX_HISTORY = "presentation-image.history.max-per-user";
    private static final String PRESENTATION_IMAGE_MAX_GLOBAL_HISTORY = "presentation-image.history.max-total";
    private static final String MATCHMAKING_MAX_HISTORY = "matchmaking.history.max-per-user";
    private static final String MATCHMAKING_MAX_GLOBAL_HISTORY = "matchmaking.history.max-total";
    private static final String GITHUB_RANKING_ENABLED = "github.ranking.enabled";
    private static final String GITHUB_RANKING_INTERVAL_HOURS = "github.ranking.interval.hours";
    private static final String GITHUB_RANKING_MANUAL_COOLDOWN_MINUTES = "github.ranking.manual.cooldown.minutes";
    private static final String GITHUB_RANKING_WEEKLY_LIMIT = "github.ranking.weekly.limit";
    private static final String GITHUB_RANKING_MONTHLY_LIMIT = "github.ranking.monthly.limit";
    private static final String GITHUB_RANKING_AI_ENABLED = "github.ranking.ai.enabled";
    private static final Map<String, String> VISIBILITY_DEFAULTS = Map.of(
            "Publications", "PUBLIC", "Translate", "USER", "Contact", "USER",
            "ImageGenerate", "USER", "News", "PUBLIC", "Guestbook", "PUBLIC", "Matchmaking", "USER");
    private static final String VISIBILITY_POLICY_VERSION = "visibility.policy.version";

    private final JdbcTemplate jdbc;
    private final LlmConfig llm;
    private final BabelDocConfig babeldoc;
    private final ZoteroConfig zotero;
    private final PptGenerationConfig pptGeneration;
    private final TranslationConfig translation;
    private final ImageGenerationConfig imageGeneration;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RuntimeConfigService(JdbcTemplate jdbc, LlmConfig llm, BabelDocConfig babeldoc, ZoteroConfig zotero) {
        this(jdbc, llm, babeldoc, zotero, null, null, null);
    }

    @Autowired
    public RuntimeConfigService(JdbcTemplate jdbc, LlmConfig llm, BabelDocConfig babeldoc,
                                ZoteroConfig zotero, PptGenerationConfig pptGeneration, TranslationConfig translation,
                                ImageGenerationConfig imageGeneration) {
        this.jdbc = jdbc;
        this.llm = llm;
        this.babeldoc = babeldoc;
        this.zotero = zotero;
        this.pptGeneration = pptGeneration;
        this.translation = translation;
        this.imageGeneration = imageGeneration;
    }

    /** 将上一轮错误的一刀切登录策略恢复为原有默认值；之后完全由 root 后台配置。 */
    @PostConstruct
    public void migrateVisibilityDefaults() {
        if ("6".equals(value(VISIBILITY_POLICY_VERSION, ""))) return;
        VISIBILITY_DEFAULTS.forEach((feature, level) -> {
            String key = "visibility." + feature;
            if (jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?", String.class, key).isEmpty()) {
                save(key, level);
            }
        });
        save(VISIBILITY_POLICY_VERSION, "6");
    }

    public String llmUrl() { return value(LLM_URL, llm.getApiUrl()); }
    public String llmKey() { return value(LLM_KEY, llm.getApiKey()); }
    public String llmModel() { return value(LLM_MODEL, llm.getModel()); }
    public String llmProtocol() { return normalizeProtocol(value(LLM_PROTOCOL, "auto")); }
    public String resolvedLlmProtocol() {
        return resolveLlmProtocol(llmUrl(), llmProtocol());
    }
    public String resolveLlmProtocol(String baseUrl, String protocol) {
        String configured = normalizeProtocol(protocol);
        if (!"AUTO".equals(configured)) return configured;
        String url = (baseUrl == null ? "" : baseUrl).toLowerCase();
        return url.contains("anthropic") || url.endsWith("/messages") ? "CLAUDE" : "OPENAI";
    }
    public String llmEndpoint() {
        return llmEndpoint(llmUrl(), llmProtocol());
    }
    public String llmEndpoint(String baseUrl, String protocol) {
        String base = (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "");
        if ("CLAUDE".equals(resolveLlmProtocol(baseUrl, protocol))) {
            if (base.endsWith("/messages")) return base;
            return base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
        }
        if (base.endsWith("/chat/completions")) return base;
        return base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
    }
    /** 婚恋报告默认继承通用 Mimo 配置；后台填写后只影响该节目。 */
    public String matchmakingLlmUrl() { return value(MATCHMAKING_LLM_URL, llmUrl()); }
    public String matchmakingLlmKey() { return value(MATCHMAKING_LLM_KEY, llmKey()); }
    public String matchmakingLlmModel() { return value(MATCHMAKING_LLM_MODEL, llmModel()); }
    public String matchmakingLlmProtocol() { return normalizeProtocol(value(MATCHMAKING_LLM_PROTOCOL, llmProtocol())); }
    public String resolvedMatchmakingLlmProtocol() { return resolveLlmProtocol(matchmakingLlmUrl(), matchmakingLlmProtocol()); }
    public String matchmakingLlmEndpoint() { return llmEndpoint(matchmakingLlmUrl(), matchmakingLlmProtocol()); }
    public String babelUrl() { return value(BABEL_URL, babeldoc.getOpenaiBaseUrl()); }
    public String babelKey() { return value(BABEL_KEY, babeldoc.getOpenaiApiKey()); }
    public String babelModel() { return value(BABEL_MODEL, babeldoc.getOpenaiModel()); }
    public String zoteroUrl() { return value(ZOTERO_URL, zotero.getBaseUrl()); }
    public String zoteroKey() { return value(ZOTERO_KEY, zotero.getApiKey()); }
    public String zoteroUser() { return value(ZOTERO_USER, zotero.getUserId()); }
    public String tavilyUrl() { return value(TAVILY_URL, System.getenv().getOrDefault("TAVILY_API_URL", "https://api.tavily.com/search")); }
    public String tavilyKey() { return value(TAVILY_KEY, System.getenv().getOrDefault("TAVILY_API_KEY", "")); }
    public int tavilyMaxSearches() {
        int fallback = clamp(intValue(System.getenv("TAVILY_MAX_SEARCHES"), 6), 1, 12);
        return safeInt(TAVILY_MAX_SEARCHES, fallback, 1, 12);
    }
    public String semanticScholarKey() { return value(SEMANTIC_SCHOLAR_KEY, System.getenv().getOrDefault("SEMANTIC_SCHOLAR_API_KEY", "")); }
    public String codexPptKey() { return value(CODEX_PPT_KEY, System.getenv().getOrDefault("PPT_GENERATION_CODEX_API_KEY", "")); }
    public String codexPptModel() {
        String model = value(CODEX_PPT_MODEL, "gpt-5.6-terra");
        return CODEX_MODELS.contains(model) ? model : "gpt-5.6-terra";
    }
    public String codexPptReasoningEffort() {
        String effort = value(CODEX_PPT_REASONING, "high").toLowerCase();
        return CODEX_REASONING.contains(effort) ? effort : "high";
    }
    /** Empty keeps the normal OpenAI CLI route; a configured URL enables the isolated CCSwitch Responses provider. */
    public String codexPptProviderBaseUrl() {
        String value = value(CODEX_PPT_PROVIDER_BASE_URL, "");
        return normalizeCodexPptProviderBaseUrl(value);
    }

    /** Validate a transient admin-test value using the same rules as the persisted provider setting. */
    public String normalizeCodexPptProviderBaseUrl(String value) {
        return value == null || value.isBlank() ? "" : url(value, "");
    }
    /** Accept an OpenAI-compatible base URL or the complete generations endpoint. */
    public String imageGenerationEndpoint() {
        String base = value(IMAGE_GENERATION_URL,
                pptGeneration == null ? "https://api.openai.com/v1" : pptGeneration.getImageGenerationEndpoint());
        return imageGenerationEndpoint(base);
    }
    private String imageGenerationEndpoint(String base) {
        base = url(base, "https://api.openai.com/v1");
        if (base.endsWith("/images/edits")) base = base.substring(0, base.length() - "/images/edits".length());
        return base.endsWith("/images/generations") ? base
                : (base.endsWith("/v1") ? base : base + "/v1") + "/images/generations";
    }
    public String imageEditEndpoint() {
        String generations = imageGenerationEndpoint();
        return generations.substring(0, generations.length() - "/generations".length()) + "/edits";
    }
    public String imageGenerationKey() {
        return value(IMAGE_GENERATION_KEY,
                pptGeneration == null ? "" : pptGeneration.getImageGenerationKey());
    }
    public String imageGenerationModel() {
        String model = value(IMAGE_GENERATION_MODEL,
                pptGeneration == null ? "gpt-image-2" : pptGeneration.getImageGenerationModel());
        return model.matches("[A-Za-z0-9._:-]{1,100}") ? model : "gpt-image-2";
    }
    public String imageGenerationQuality() {
        String quality = value(IMAGE_GENERATION_QUALITY,
                pptGeneration == null ? "medium" : pptGeneration.getImageGenerationQuality()).toLowerCase();
        return Set.of("low", "medium", "high").contains(quality) ? quality : "medium";
    }
    public int imageGenerationMaxImages() {
        int fallback = pptGeneration == null ? 3 : pptGeneration.getImageGenerationMaxImages();
        return safeInt(IMAGE_GENERATION_MAX_IMAGES, fallback, 1, 10);
    }

    /**
     * Verifies the Images route without creating a billable image.  Some relays
     * intentionally expose /images/generations but not /models, so a 404 model
     * lookup falls back to an empty JSON request to the actual Images endpoint.
     * The fallback is only considered healthy when it returns a client validation
     * status (400/405/422); it never sends a prompt or image-generation payload.
     */
    public Map<String, Object> testImageGenerationConnection(String baseUrl, String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) throw new AuthException(400, "Images API Key 未配置");
        String cleanModel = model == null ? "" : model.trim();
        if (!cleanModel.matches("[A-Za-z0-9._:-]{1,100}")) throw new AuthException(400, "Image 模型名称不合法");
        try {
            String generations = imageGenerationEndpoint(baseUrl);
            int marker = generations.lastIndexOf("/images/generations");
            if (marker < 0) throw new AuthException(400, "Images API 地址不合法");
            URI endpoint = URI.create(generations.substring(0, marker) + "/models/" + cleanModel);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET().build();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Map.of("message", "Images API 认证与路由正常（未生成图片）", "configured", true,
                        "model", cleanModel);
            }
            if (response.statusCode() != 404) {
                throw imageGenerationTestFailure(response.statusCode(), response.body());
            }
            HttpRequest fallback = HttpRequest.newBuilder(URI.create(generations))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)).build();
            HttpResponse<String> fallbackResponse = client.send(fallback, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = fallbackResponse.statusCode();
            if (status == 400 || status == 405 || status == 422) {
                return Map.of("message", "Images 生成路径可达（中转未实现 Models API；未生成图片）", "configured", true,
                        "model", cleanModel);
            }
            throw imageGenerationTestFailure(status, fallbackResponse.body());
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException(502, "Images API 延迟测试失败：" + conciseMessage(e));
        }
    }

    /** Keep vendor diagnostics out of the browser while making a common relay entitlement error actionable. */
    private AuthException imageGenerationTestFailure(int status, String responseBody) {
        String normalized = responseBody == null ? "" : responseBody.toLowerCase(Locale.ROOT);
        if (status == 403 && normalized.contains("image generation is not enabled")) {
            return new AuthException(422, "Images API 已到达，但该中转账户/分组未开通图像生成（HTTP 403）");
        }
        if (status == 401) return new AuthException(401, "Images API 认证失败（HTTP 401），请检查独立 Images Key");
        if (status == 403) return new AuthException(403, "Images API 被上游拒绝（HTTP 403），请检查中转账户权限");
        return new AuthException(502, "Images API 延迟测试失败：HTTP " + status);
    }
    public String visibilityLevel(String feature) { return value("visibility." + feature, VISIBILITY_DEFAULTS.getOrDefault(feature, "PUBLIC")); }

    public Map<String, Object> publicSettings() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("llm", provider("LLM / 通用生成", llmUrl(), llmModel(), llmKey(), llmProtocol(), resolvedLlmProtocol()));
        data.put("matchmaking", provider("婚恋报告 / Mimo", matchmakingLlmUrl(), matchmakingLlmModel(), matchmakingLlmKey(), matchmakingLlmProtocol(), resolvedMatchmakingLlmProtocol()));
        data.put("babeldoc", provider("BabelDOC / PDF 翻译", babelUrl(), babelModel(), babelKey(), "openai", "OPENAI"));
        data.put("zotero", new LinkedHashMap<>(Map.of(
                "name", "Zotero 文献库", "baseUrl", zoteroUrl(), "userId", zoteroUser(),
                "configured", !zoteroKey().isBlank() && !zoteroUser().isBlank(),
                "apiKeyConfigured", !zoteroKey().isBlank(),
                "apiKeyHint", zoteroKey().isBlank() ? "未配置" : "已配置（" + zoteroKey().substring(Math.max(0, zoteroKey().length() - 4)) + "）")));
        data.put("githubRanking", githubRankingSettings());
        data.put("pptRetention", pptRetentionSettings());
        data.put("translationRetention", translationRetentionSettings());
        data.put("imageRetention", imageRetentionSettings());
        data.put("presentationImageRetention", presentationImageRetentionSettings());
        data.put("matchmakingRetention", matchmakingRetentionSettings());
        data.put("research", new LinkedHashMap<>(Map.of(
                "name", "Tavily / 演示研究",
                "baseUrl", tavilyUrl(),
                "maxSearches", tavilyMaxSearches(),
                "configured", !tavilyKey().isBlank(),
                "apiKeyConfigured", !tavilyKey().isBlank(),
                "apiKeyHint", secretHint(tavilyKey()),
                "semanticScholarKeyHint", secretHint(semanticScholarKey()))));
        data.put("codexPpt", new LinkedHashMap<>(Map.of(
                "name", "Codex 演示生成", "model", codexPptModel(),
                "reasoningEffort", codexPptReasoningEffort(), "cliVersion", "0.147.0",
                "providerBaseUrl", codexPptProviderBaseUrl(),
                "configured", !codexPptKey().isBlank(), "apiKeyConfigured", !codexPptKey().isBlank(),
                "apiKeyHint", secretHint(codexPptKey()))));
        data.put("imageGeneration", new LinkedHashMap<>(Map.of(
                "name", "GPT Image 2", "baseUrl", imageGenerationEndpoint(), "model", imageGenerationModel(),
                "quality", imageGenerationQuality(), "maxImages", imageGenerationMaxImages(),
                "configured", !imageGenerationKey().isBlank(), "apiKeyConfigured", !imageGenerationKey().isBlank(),
                "apiKeyHint", secretHint(imageGenerationKey()))));
        Map<String, String> visibility = new LinkedHashMap<>();
        VISIBILITY_DEFAULTS.forEach((feature, fallback) -> visibility.put(feature, value("visibility." + feature, fallback)));
        data.put("visibility", visibility);
        return data;
    }

    @Transactional
    public void update(Map<String, Object> body) {
        Map<String, Object> llmBody = map(body.get("llm"));
        Map<String, Object> matchmakingBody = map(body.get("matchmaking"));
        Map<String, Object> babelBody = map(body.get("babeldoc"));
        Map<String, Object> zoteroBody = map(body.get("zotero"));
        save(LLM_URL, url(string(llmBody, "baseUrl"), llmUrl()));
        save(LLM_MODEL, text(string(llmBody, "model"), llmModel()));
        save(LLM_PROTOCOL, normalizeProtocol(string(llmBody, "protocol").isBlank() ? llmProtocol() : string(llmBody, "protocol")));
        saveSecret(LLM_KEY, llmBody.get("apiKey"), llmKey());
        if (!matchmakingBody.isEmpty()) {
            save(MATCHMAKING_LLM_URL, url(string(matchmakingBody, "baseUrl"), matchmakingLlmUrl()));
            save(MATCHMAKING_LLM_MODEL, text(string(matchmakingBody, "model"), matchmakingLlmModel()));
            save(MATCHMAKING_LLM_PROTOCOL, normalizeProtocol(string(matchmakingBody, "protocol").isBlank()
                    ? matchmakingLlmProtocol() : string(matchmakingBody, "protocol")));
            saveSecret(MATCHMAKING_LLM_KEY, matchmakingBody.get("apiKey"), matchmakingLlmKey());
        }
        save(BABEL_URL, url(string(babelBody, "baseUrl"), babelUrl()));
        save(BABEL_MODEL, text(string(babelBody, "model"), babelModel()));
        saveSecret(BABEL_KEY, babelBody.get("apiKey"), babelKey());
        save(ZOTERO_URL, url(string(zoteroBody, "baseUrl"), zoteroUrl()));
        save(ZOTERO_USER, text(string(zoteroBody, "userId"), zoteroUser()));
        saveSecret(ZOTERO_KEY, zoteroBody.get("apiKey"), zoteroKey());
        Map<String, Object> researchBody = map(body.get("research"));
        if (!researchBody.isEmpty()) {
            save(TAVILY_URL, url(string(researchBody, "baseUrl"), tavilyUrl()));
            save(TAVILY_MAX_SEARCHES, Integer.toString(clamp(intValue(researchBody.get("maxSearches"), tavilyMaxSearches()), 1, 12)));
            saveSecret(TAVILY_KEY, researchBody.get("apiKey"), tavilyKey());
            saveSecret(SEMANTIC_SCHOLAR_KEY, researchBody.get("semanticScholarApiKey"), semanticScholarKey());
        }
        Map<String, Object> codexPptBody = map(body.get("codexPpt"));
        if (!codexPptBody.isEmpty()) {
            String model = string(codexPptBody, "model").trim();
            String effort = string(codexPptBody, "reasoningEffort").trim().toLowerCase();
            if (!CODEX_MODELS.contains(model)) throw new AuthException(400, "Codex PPT 模型不在允许列表");
            if (!CODEX_REASONING.contains(effort)) throw new AuthException(400, "Codex reasoning effort 不合法");
            save(CODEX_PPT_MODEL, model);
            save(CODEX_PPT_REASONING, effort);
            String providerBaseUrl = string(codexPptBody, "providerBaseUrl");
            save(CODEX_PPT_PROVIDER_BASE_URL, normalizeCodexPptProviderBaseUrl(providerBaseUrl));
            saveSecret(CODEX_PPT_KEY, codexPptBody.get("apiKey"), codexPptKey());
        }
        Map<String, Object> imageGenerationBody = map(body.get("imageGeneration"));
        if (!imageGenerationBody.isEmpty()) {
            save(IMAGE_GENERATION_URL, imageGenerationEndpoint(text(string(imageGenerationBody, "baseUrl"), imageGenerationEndpoint())));
            String model = text(string(imageGenerationBody, "model"), imageGenerationModel());
            if (!model.matches("[A-Za-z0-9._:-]{1,100}")) throw new AuthException(400, "Image 模型名称不合法");
            String quality = text(string(imageGenerationBody, "quality"), imageGenerationQuality()).toLowerCase();
            if (!Set.of("low", "medium", "high").contains(quality)) throw new AuthException(400, "Image 质量只能是 low、medium 或 high");
            save(IMAGE_GENERATION_MODEL, model);
            save(IMAGE_GENERATION_QUALITY, quality);
            save(IMAGE_GENERATION_MAX_IMAGES, Integer.toString(clamp(intValue(imageGenerationBody.get("maxImages"), imageGenerationMaxImages()), 1, 10)));
            saveSecret(IMAGE_GENERATION_KEY, imageGenerationBody.get("apiKey"), imageGenerationKey());
        }
        Map<String, Object> rankingBody = map(body.get("githubRanking"));
        if (!rankingBody.isEmpty()) {
            save(GITHUB_RANKING_ENABLED, Boolean.toString(booleanValue(rankingBody.get("enabled"), githubRankingEnabled())));
            save(GITHUB_RANKING_INTERVAL_HOURS, Integer.toString(clamp(intValue(rankingBody.get("refreshIntervalHours"), githubRankingIntervalHours()), 6, 168)));
            save(GITHUB_RANKING_MANUAL_COOLDOWN_MINUTES, Integer.toString(clamp(intValue(rankingBody.get("manualCooldownMinutes"), githubRankingManualCooldownMinutes()), 15, 1440)));
            save(GITHUB_RANKING_WEEKLY_LIMIT, Integer.toString(clamp(intValue(rankingBody.get("weeklyLimit"), githubRankingWeeklyLimit()), 1, 20)));
            save(GITHUB_RANKING_MONTHLY_LIMIT, Integer.toString(clamp(intValue(rankingBody.get("monthlyLimit"), githubRankingMonthlyLimit()), 1, 20)));
            save(GITHUB_RANKING_AI_ENABLED, Boolean.toString(booleanValue(rankingBody.get("aiSummaryEnabled"), githubRankingAiEnabled())));
        }
        Map<String, Object> pptRetentionBody = map(body.get("pptRetention"));
        if (!pptRetentionBody.isEmpty()) {
            int maxPerUser = clamp(intValue(pptRetentionBody.get("maxPerUser"), pptMaxHistory()), 1, 100);
            int maxTotal = clamp(intValue(pptRetentionBody.get("maxTotal"), pptMaxGlobalHistory()), 1, 1000);
            save(PPT_MAX_HISTORY, Integer.toString(maxPerUser));
            save(PPT_MAX_GLOBAL_HISTORY, Integer.toString(maxTotal));
        }
        Map<String, Object> translationRetentionBody = map(body.get("translationRetention"));
        if (!translationRetentionBody.isEmpty()) {
            int maxPerUser = clamp(intValue(translationRetentionBody.get("maxPerUser"), translationMaxHistory()), 1, 100);
            int maxTotal = clamp(intValue(translationRetentionBody.get("maxTotal"), translationMaxGlobalHistory()), 1, 1000);
            save(TRANSLATION_MAX_HISTORY, Integer.toString(maxPerUser));
            save(TRANSLATION_MAX_GLOBAL_HISTORY, Integer.toString(maxTotal));
        }
        Map<String, Object> imageRetentionBody = map(body.get("imageRetention"));
        if (!imageRetentionBody.isEmpty()) {
            int maxPerUser = clamp(intValue(imageRetentionBody.get("maxPerUser"), imageMaxHistory()), 1, 100);
            int maxTotal = clamp(intValue(imageRetentionBody.get("maxTotal"), imageMaxGlobalHistory()), 1, 1000);
            save(IMAGE_MAX_HISTORY, Integer.toString(maxPerUser));
            save(IMAGE_MAX_GLOBAL_HISTORY, Integer.toString(maxTotal));
        }
        Map<String, Object> presentationImageRetentionBody = map(body.get("presentationImageRetention"));
        if (!presentationImageRetentionBody.isEmpty()) {
            save(PRESENTATION_IMAGE_MAX_HISTORY, Integer.toString(clamp(intValue(presentationImageRetentionBody.get("maxPerUser"), presentationImageMaxHistory()), 1, 100)));
            save(PRESENTATION_IMAGE_MAX_GLOBAL_HISTORY, Integer.toString(clamp(intValue(presentationImageRetentionBody.get("maxTotal"), presentationImageMaxGlobalHistory()), 1, 1000)));
        }
        Map<String, Object> matchmakingRetentionBody = map(body.get("matchmakingRetention"));
        if (!matchmakingRetentionBody.isEmpty()) {
            save(MATCHMAKING_MAX_HISTORY, Integer.toString(clamp(intValue(matchmakingRetentionBody.get("maxPerUser"), matchmakingMaxHistory()), 1, 100)));
            save(MATCHMAKING_MAX_GLOBAL_HISTORY, Integer.toString(clamp(intValue(matchmakingRetentionBody.get("maxTotal"), matchmakingMaxGlobalHistory()), 1, 1000)));
        }
        Map<String, Object> visibility = map(body.get("visibility"));
        VISIBILITY_DEFAULTS.forEach((feature, fallback) -> {
            String level = string(visibility, feature).toUpperCase();
            List<String> allowed = "ImageGenerate".equals(feature) ? List.of("USER", "ROOT") : List.of("PUBLIC", "USER", "ROOT");
            save("visibility." + feature, allowed.contains(level) ? level : value("visibility." + feature, fallback));
        });
    }

    private Map<String, Object> provider(String name, String baseUrl, String model, String key, String protocol, String resolvedProtocol) {
        return new LinkedHashMap<>(Map.of("name", name, "baseUrl", baseUrl, "model", model, "protocol", protocol,
                "resolvedProtocol", resolvedProtocol,
                "configured", !key.isBlank(), "apiKeyConfigured", !key.isBlank(),
                "apiKeyHint", key.isBlank() ? "未配置" : "已配置（" + key.substring(Math.max(0, key.length() - 4)) + "）"));
    }

    public Map<String, Object> testTavilyConnection(String baseUrl, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) throw new AuthException(400, "Tavily API Key 未配置");
        try {
            URI endpoint = URI.create(url(baseUrl, tavilyUrl()));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"query\":\"OpenAI\",\"search_depth\":\"basic\",\"max_results\":1,\"include_answer\":false}",
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AuthException(502, "Tavily 连通性测试失败：HTTP " + response.statusCode());
            }
            return Map.of("message", "Tavily 搜索连接成功", "configured", true);
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException(502, "Tavily 连通性测试失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    private String value(String key, String fallback) {
        List<String> values = jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?", String.class, key);
        return values.isEmpty() || values.get(0).isBlank() ? (fallback == null ? "" : fallback) : values.get(0);
    }
    private void save(String key, String value) {
        int updated = jdbc.update("UPDATE app_settings SET setting_value=?, updated_at=CURRENT_TIMESTAMP WHERE setting_key=?", value, key);
        if (updated == 0) jdbc.update("INSERT INTO app_settings (setting_key, setting_value) VALUES (?, ?)", key, value);
    }
    private void saveSecret(String key, Object submitted, String current) {
        String value = submitted == null ? "" : submitted.toString().trim();
        if (!value.isBlank() && !"未配置".equals(value) && !value.startsWith("已配置（")) save(key, value);
        else if (current.isBlank()) save(key, "");
    }
    private String secretHint(String value) {
        return value == null || value.isBlank() ? "未配置" : "已配置（" + value.substring(Math.max(0, value.length() - 4)) + "）";
    }
    /** Root-only connection tests may surface a short provider error, never an API key or whole body. */
    private String conciseUpstreamError(String responseBody, String apiKey) {
        String body = responseBody == null ? "" : responseBody.trim();
        String detail = "";
        try {
            Map<String, Object> root = objectMapper.readValue(body, Map.class);
            Object error = root.get("error");
            if (error instanceof Map<?, ?> errorMap) {
                Object message = errorMap.containsKey("message") ? errorMap.get("message") : errorMap.get("msg");
                detail = message == null ? "" : String.valueOf(message);
            }
            if (detail.isBlank()) detail = String.valueOf(root.getOrDefault("message", root.getOrDefault("msg", "")));
        } catch (Exception ignored) { }
        if (detail.isBlank()) detail = body;
        detail = detail.replace(apiKey == null ? "" : apiKey, "***").replaceAll("[\\r\\n\\t]+", " ").trim();
        return detail.isBlank() ? "上游未返回详情" : detail.substring(0, Math.min(detail.length(), 360));
    }
    private String conciseMessage(Exception error) {
        String message = error == null || error.getMessage() == null ? "未知错误" : error.getMessage();
        String clean = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.substring(0, Math.min(clean.length(), 240));
    }
    private String text(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    public Map<String, Object> githubRankingSettings() {
        return new LinkedHashMap<>(Map.of(
                "enabled", githubRankingEnabled(),
                "refreshIntervalHours", githubRankingIntervalHours(),
                "manualCooldownMinutes", githubRankingManualCooldownMinutes(),
                "weeklyLimit", githubRankingWeeklyLimit(),
                "monthlyLimit", githubRankingMonthlyLimit(),
                "aiSummaryEnabled", githubRankingAiEnabled()));
    }
    public boolean githubRankingEnabled() { return Boolean.parseBoolean(value(GITHUB_RANKING_ENABLED, "true")); }
    public int githubRankingIntervalHours() { return safeInt(GITHUB_RANKING_INTERVAL_HOURS, 24, 6, 168); }
    public int githubRankingManualCooldownMinutes() { return safeInt(GITHUB_RANKING_MANUAL_COOLDOWN_MINUTES, 30, 15, 1440); }
    public int githubRankingWeeklyLimit() { return safeInt(GITHUB_RANKING_WEEKLY_LIMIT, 10, 1, 20); }
    public int githubRankingMonthlyLimit() { return safeInt(GITHUB_RANKING_MONTHLY_LIMIT, 10, 1, 20); }
    public boolean githubRankingAiEnabled() { return Boolean.parseBoolean(value(GITHUB_RANKING_AI_ENABLED, "true")); }
    public Map<String, Object> pptRetentionSettings() {
        return new LinkedHashMap<>(Map.of(
                "maxPerUser", pptMaxHistory(),
                "maxTotal", pptMaxGlobalHistory()));
    }
    public int pptMaxHistory() {
        return safeInt(PPT_MAX_HISTORY, pptGeneration == null ? 5 : pptGeneration.getMaxHistory(), 1, 100);
    }
    public int pptMaxGlobalHistory() {
        int fallback = pptGeneration == null ? 20 : pptGeneration.getMaxGlobalHistory();
        return safeInt(PPT_MAX_GLOBAL_HISTORY, fallback, 1, 1000);
    }
    public Map<String, Object> translationRetentionSettings() {
        return new LinkedHashMap<>(Map.of(
                "maxPerUser", translationMaxHistory(),
                "maxTotal", translationMaxGlobalHistory()));
    }
    public int translationMaxHistory() {
        return safeInt(TRANSLATION_MAX_HISTORY, translation == null ? 5 : translation.getMaxHistory(), 1, 100);
    }
    public int translationMaxGlobalHistory() {
        int fallback = translation == null ? 20 : translation.getMaxGlobalHistory();
        return safeInt(TRANSLATION_MAX_GLOBAL_HISTORY, fallback, 1, 1000);
    }
    public Map<String, Object> imageRetentionSettings() {
        return new LinkedHashMap<>(Map.of(
                "maxPerUser", imageMaxHistory(),
                "maxTotal", imageMaxGlobalHistory()));
    }
    public int imageMaxHistory() {
        return safeInt(IMAGE_MAX_HISTORY, imageGeneration == null ? 5 : imageGeneration.getMaxHistory(), 1, 100);
    }
    public int imageMaxGlobalHistory() {
        int fallback = imageGeneration == null ? 20 : imageGeneration.getMaxGlobalHistory();
        return safeInt(IMAGE_MAX_GLOBAL_HISTORY, fallback, 1, 1000);
    }
    public Map<String, Object> presentationImageRetentionSettings() {
        return new LinkedHashMap<>(Map.of("maxPerUser", presentationImageMaxHistory(), "maxTotal", presentationImageMaxGlobalHistory()));
    }
    public int presentationImageMaxHistory() { return safeInt(PRESENTATION_IMAGE_MAX_HISTORY, 20, 1, 100); }
    public int presentationImageMaxGlobalHistory() { return safeInt(PRESENTATION_IMAGE_MAX_GLOBAL_HISTORY, 100, 1, 1000); }
    public Map<String, Object> matchmakingRetentionSettings() {
        return new LinkedHashMap<>(Map.of("maxPerUser", matchmakingMaxHistory(), "maxTotal", matchmakingMaxGlobalHistory()));
    }
    public int matchmakingMaxHistory() { return safeInt(MATCHMAKING_MAX_HISTORY, 20, 1, 100); }
    public int matchmakingMaxGlobalHistory() { return safeInt(MATCHMAKING_MAX_GLOBAL_HISTORY, 200, 1, 1000); }
    private int safeInt(String key, int fallback, int min, int max) { return clamp(intValue(value(key, Integer.toString(fallback)), fallback), min, max); }
    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private int intValue(Object value, int fallback) { try { return Integer.parseInt(value == null ? "" : value.toString().trim()); } catch (Exception e) { return fallback; } }
    private boolean booleanValue(Object value, boolean fallback) { return value == null ? fallback : Boolean.parseBoolean(value.toString()); }
    private String url(String value, String fallback) { String result = text(value, fallback); try { URI uri = URI.create(result); if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) throw new IllegalArgumentException(); return result.replaceAll("/+$", ""); } catch (Exception e) { throw new AuthException(400, "API 地址必须是有效的 http/https URL"); } }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of(); }
    private String string(Map<String, Object> body, String key) { Object value = body.get(key); return value == null ? "" : value.toString().trim(); }
    private String normalizeProtocol(String value) {
        String protocol = value == null ? "AUTO" : value.trim().toUpperCase();
        if ("ANTHROPIC".equals(protocol)) protocol = "CLAUDE";
        if (!List.of("AUTO", "OPENAI", "CLAUDE").contains(protocol)) throw new AuthException(400, "LLM 协议只能是自动、OpenAI 或 Claude");
        return protocol;
    }
}
