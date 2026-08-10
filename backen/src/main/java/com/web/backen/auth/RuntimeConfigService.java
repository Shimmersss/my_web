package com.web.backen.auth;

import com.web.backen.config.BabelDocConfig;
import com.web.backen.config.LlmConfig;
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
import java.util.Map;
import java.util.Set;

/** 后台可维护的运行时配置。密钥只在后端内存和数据库中保存，接口永不返回明文。 */
@Service
public class RuntimeConfigService {
    private static final String LLM_URL = "api.llm.url";
    private static final String LLM_KEY = "api.llm.key";
    private static final String LLM_MODEL = "api.llm.model";
    private static final String LLM_PROTOCOL = "api.llm.protocol";
    private static final String BABEL_URL = "api.babeldoc.url";
    private static final String BABEL_KEY = "api.babeldoc.key";
    private static final String BABEL_MODEL = "api.babeldoc.model";
    private static final String ZOTERO_URL = "api.zotero.url";
    private static final String ZOTERO_KEY = "api.zotero.key";
    private static final String ZOTERO_USER = "api.zotero.user";
    private static final String TAVILY_URL = "api.tavily.url";
    private static final String TAVILY_KEY = "api.tavily.key";
    private static final String TAVILY_MAX_SEARCHES = "api.tavily.max-searches";
    private static final String MIMO_SEARCH_URL = "ppt.mimo-search.url";
    private static final String MIMO_SEARCH_KEY = "ppt.mimo-search.key";
    private static final String MIMO_SEARCH_MODEL = "ppt.mimo-search.model";
    private static final String SEMANTIC_SCHOLAR_KEY = "api.semantic-scholar.key";
    private static final String CODEX_PPT_KEY = "ppt.codex.api-key";
    private static final String CODEX_PPT_MODEL = "ppt.codex.model";
    private static final String CODEX_PPT_REASONING = "ppt.codex.reasoning-effort";
    private static final Set<String> CODEX_MODELS = Set.of("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna");
    private static final Set<String> CODEX_REASONING = Set.of("low", "medium", "high", "xhigh", "max", "ultra");
    private static final String PPT_MAX_HISTORY = "ppt.history.max-per-user";
    private static final String PPT_MAX_GLOBAL_HISTORY = "ppt.history.max-total";
    private static final String TRANSLATION_MAX_HISTORY = "translation.history.max-per-user";
    private static final String TRANSLATION_MAX_GLOBAL_HISTORY = "translation.history.max-total";
    private static final String GITHUB_RANKING_ENABLED = "github.ranking.enabled";
    private static final String GITHUB_RANKING_INTERVAL_HOURS = "github.ranking.interval.hours";
    private static final String GITHUB_RANKING_MANUAL_COOLDOWN_MINUTES = "github.ranking.manual.cooldown.minutes";
    private static final String GITHUB_RANKING_WEEKLY_LIMIT = "github.ranking.weekly.limit";
    private static final String GITHUB_RANKING_MONTHLY_LIMIT = "github.ranking.monthly.limit";
    private static final String GITHUB_RANKING_AI_ENABLED = "github.ranking.ai.enabled";
    private static final Map<String, String> VISIBILITY_DEFAULTS = Map.of(
            "Publications", "PUBLIC", "Translate", "USER", "Contact", "USER", "News", "PUBLIC");
    private static final String VISIBILITY_POLICY_VERSION = "visibility.policy.version";

    private final JdbcTemplate jdbc;
    private final LlmConfig llm;
    private final BabelDocConfig babeldoc;
    private final ZoteroConfig zotero;
    private final PptGenerationConfig pptGeneration;
    private final TranslationConfig translation;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RuntimeConfigService(JdbcTemplate jdbc, LlmConfig llm, BabelDocConfig babeldoc, ZoteroConfig zotero) {
        this(jdbc, llm, babeldoc, zotero, null, null);
    }

    @Autowired
    public RuntimeConfigService(JdbcTemplate jdbc, LlmConfig llm, BabelDocConfig babeldoc,
                                ZoteroConfig zotero, PptGenerationConfig pptGeneration, TranslationConfig translation) {
        this.jdbc = jdbc;
        this.llm = llm;
        this.babeldoc = babeldoc;
        this.zotero = zotero;
        this.pptGeneration = pptGeneration;
        this.translation = translation;
    }

    /** 将上一轮错误的一刀切登录策略恢复为原有默认值；之后完全由 root 后台配置。 */
    @PostConstruct
    public void migrateVisibilityDefaults() {
        if ("3".equals(value(VISIBILITY_POLICY_VERSION, ""))) return;
        VISIBILITY_DEFAULTS.forEach((feature, level) -> save("visibility." + feature, level));
        save(VISIBILITY_POLICY_VERSION, "3");
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
    /**
     * MiMo documents an OpenAI-style Base URL ending in /v1, whereas earlier
     * versions of the admin card asked for the complete endpoint. Accept both
     * forms so a documented Base URL is not posted directly and rejected as a
     * 404 by OpenResty.
     */
    public String mimoSearchEndpoint() {
        return llmEndpoint(value(MIMO_SEARCH_URL,
                pptGeneration == null ? "" : pptGeneration.getMimoSearchEndpoint()), "OPENAI");
    }
    public String mimoSearchKey() { return value(MIMO_SEARCH_KEY, pptGeneration == null ? "" : pptGeneration.getMimoSearchKey()); }
    public String mimoSearchModel() { return value(MIMO_SEARCH_MODEL, pptGeneration == null ? "mimo-v2.5" : pptGeneration.getMimoSearchModel()); }
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
    public String visibilityLevel(String feature) { return value("visibility." + feature, VISIBILITY_DEFAULTS.getOrDefault(feature, "PUBLIC")); }

    public Map<String, Object> publicSettings() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("llm", provider("LLM / 通用生成", llmUrl(), llmModel(), llmKey(), llmProtocol(), resolvedLlmProtocol()));
        data.put("babeldoc", provider("BabelDOC / PDF 翻译", babelUrl(), babelModel(), babelKey(), "openai", "OPENAI"));
        data.put("zotero", new LinkedHashMap<>(Map.of(
                "name", "Zotero 文献库", "baseUrl", zoteroUrl(), "userId", zoteroUser(),
                "configured", !zoteroKey().isBlank() && !zoteroUser().isBlank(),
                "apiKeyConfigured", !zoteroKey().isBlank(),
                "apiKeyHint", zoteroKey().isBlank() ? "未配置" : "已配置（" + zoteroKey().substring(Math.max(0, zoteroKey().length() - 4)) + "）")));
        data.put("githubRanking", githubRankingSettings());
        data.put("pptRetention", pptRetentionSettings());
        data.put("translationRetention", translationRetentionSettings());
        data.put("research", new LinkedHashMap<>(Map.of(
                "name", "Tavily / 演示研究",
                "baseUrl", tavilyUrl(),
                "maxSearches", tavilyMaxSearches(),
                "configured", !tavilyKey().isBlank(),
                "apiKeyConfigured", !tavilyKey().isBlank(),
                "apiKeyHint", secretHint(tavilyKey()),
                "semanticScholarKeyHint", secretHint(semanticScholarKey()))));
        data.put("mimoSearch", new LinkedHashMap<>(Map.of(
                "name", "Mimo 原生联网搜索",
                "baseUrl", mimoSearchEndpoint(),
                "model", mimoSearchModel(),
                "configured", !mimoSearchEndpoint().isBlank() && !mimoSearchKey().isBlank(),
                "apiKeyConfigured", !mimoSearchKey().isBlank(),
                "apiKeyHint", secretHint(mimoSearchKey()))));
        data.put("codexPpt", new LinkedHashMap<>(Map.of(
                "name", "Codex PPTD", "model", codexPptModel(),
                "reasoningEffort", codexPptReasoningEffort(), "cliVersion", "0.147.0",
                "configured", !codexPptKey().isBlank(), "apiKeyConfigured", !codexPptKey().isBlank(),
                "apiKeyHint", secretHint(codexPptKey()))));
        Map<String, String> visibility = new LinkedHashMap<>();
        VISIBILITY_DEFAULTS.forEach((feature, fallback) -> visibility.put(feature, value("visibility." + feature, fallback)));
        data.put("visibility", visibility);
        return data;
    }

    @Transactional
    public void update(Map<String, Object> body) {
        Map<String, Object> llmBody = map(body.get("llm"));
        Map<String, Object> babelBody = map(body.get("babeldoc"));
        Map<String, Object> zoteroBody = map(body.get("zotero"));
        save(LLM_URL, url(string(llmBody, "baseUrl"), llmUrl()));
        save(LLM_MODEL, text(string(llmBody, "model"), llmModel()));
        save(LLM_PROTOCOL, normalizeProtocol(string(llmBody, "protocol").isBlank() ? llmProtocol() : string(llmBody, "protocol")));
        saveSecret(LLM_KEY, llmBody.get("apiKey"), llmKey());
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
        Map<String, Object> mimoSearchBody = map(body.get("mimoSearch"));
        if (!mimoSearchBody.isEmpty()) {
            save(MIMO_SEARCH_URL, llmEndpoint(string(mimoSearchBody, "baseUrl"), "OPENAI"));
            save(MIMO_SEARCH_MODEL, text(string(mimoSearchBody, "model"), mimoSearchModel()));
            saveSecret(MIMO_SEARCH_KEY, mimoSearchBody.get("apiKey"), mimoSearchKey());
        }
        Map<String, Object> codexPptBody = map(body.get("codexPpt"));
        if (!codexPptBody.isEmpty()) {
            String model = string(codexPptBody, "model").trim();
            String effort = string(codexPptBody, "reasoningEffort").trim().toLowerCase();
            if (!CODEX_MODELS.contains(model)) throw new AuthException(400, "Codex PPT 模型不在允许列表");
            if (!CODEX_REASONING.contains(effort)) throw new AuthException(400, "Codex reasoning effort 不合法");
            save(CODEX_PPT_MODEL, model);
            save(CODEX_PPT_REASONING, effort);
            saveSecret(CODEX_PPT_KEY, codexPptBody.get("apiKey"), codexPptKey());
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
            int maxTotal = Math.max(maxPerUser,
                    clamp(intValue(pptRetentionBody.get("maxTotal"), pptMaxGlobalHistory()), 1, 1000));
            save(PPT_MAX_HISTORY, Integer.toString(maxPerUser));
            save(PPT_MAX_GLOBAL_HISTORY, Integer.toString(maxTotal));
        }
        Map<String, Object> translationRetentionBody = map(body.get("translationRetention"));
        if (!translationRetentionBody.isEmpty()) {
            int maxPerUser = clamp(intValue(translationRetentionBody.get("maxPerUser"), translationMaxHistory()), 1, 100);
            int maxTotal = Math.max(maxPerUser,
                    clamp(intValue(translationRetentionBody.get("maxTotal"), translationMaxGlobalHistory()), 1, 1000));
            save(TRANSLATION_MAX_HISTORY, Integer.toString(maxPerUser));
            save(TRANSLATION_MAX_GLOBAL_HISTORY, Integer.toString(maxTotal));
        }
        Map<String, Object> visibility = map(body.get("visibility"));
        VISIBILITY_DEFAULTS.forEach((feature, fallback) -> {
            String level = string(visibility, feature).toUpperCase();
            save("visibility." + feature, List.of("PUBLIC", "USER", "ROOT").contains(level) ? level : value("visibility." + feature, fallback));
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

    /** Tests the same native Mimo web_search payload used by the PPT worker. */
    public Map<String, Object> testMimoSearchConnection(String baseUrl, String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) throw new AuthException(400, "Mimo 联网搜索 API Key 未配置");
        if (model == null || model.isBlank()) throw new AuthException(400, "Mimo 联网搜索模型未配置");
        try {
            URI endpoint = URI.create(llmEndpoint(baseUrl, "OPENAI"));
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", model.trim(),
                    "messages", List.of(Map.of("role", "user", "content", "Use web search and reply only with OK.")),
                    "max_completion_tokens", 32,
                    "thinking", Map.of("type", "disabled"),
                    "tools", List.of(Map.of("type", "web_search", "max_keyword", 1, "force_search", true, "limit", 1)),
                    "tool_choice", "auto"));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AuthException(502, "Mimo 联网搜索连通性测试失败：HTTP " + response.statusCode()
                        + "：" + conciseUpstreamError(response.body(), apiKey));
            }
            return Map.of("message", "Mimo 原生联网搜索连接成功", "configured", true, "model", model.trim());
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException(502, "Mimo 联网搜索连通性测试失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
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
        return Math.max(pptMaxHistory(), safeInt(PPT_MAX_GLOBAL_HISTORY, fallback, 1, 1000));
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
        return Math.max(translationMaxHistory(), safeInt(TRANSLATION_MAX_GLOBAL_HISTORY, fallback, 1, 1000));
    }
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
