package com.web.backen.auth;

import com.web.backen.config.BabelDocConfig;
import com.web.backen.config.LlmConfig;
import com.web.backen.config.ZoteroConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 后台可维护的运行时配置。密钥只在后端内存和数据库中保存，接口永不返回明文。 */
@Service
public class RuntimeConfigService {
    private static final String LLM_URL = "api.llm.url";
    private static final String LLM_KEY = "api.llm.key";
    private static final String LLM_MODEL = "api.llm.model";
    private static final String BABEL_URL = "api.babeldoc.url";
    private static final String BABEL_KEY = "api.babeldoc.key";
    private static final String BABEL_MODEL = "api.babeldoc.model";
    private static final String ZOTERO_URL = "api.zotero.url";
    private static final String ZOTERO_KEY = "api.zotero.key";
    private static final String ZOTERO_USER = "api.zotero.user";
    private static final Map<String, String> VISIBILITY_DEFAULTS = Map.of(
            "Publications", "PUBLIC", "Translate", "USER", "Contact", "USER", "News", "PUBLIC", "Business", "PUBLIC", "Cases", "PUBLIC");

    private final JdbcTemplate jdbc;
    private final LlmConfig llm;
    private final BabelDocConfig babeldoc;
    private final ZoteroConfig zotero;

    public RuntimeConfigService(JdbcTemplate jdbc, LlmConfig llm, BabelDocConfig babeldoc, ZoteroConfig zotero) {
        this.jdbc = jdbc;
        this.llm = llm;
        this.babeldoc = babeldoc;
        this.zotero = zotero;
    }

    public String llmUrl() { return value(LLM_URL, llm.getApiUrl()); }
    public String llmKey() { return value(LLM_KEY, llm.getApiKey()); }
    public String llmModel() { return value(LLM_MODEL, llm.getModel()); }
    public String babelUrl() { return value(BABEL_URL, babeldoc.getOpenaiBaseUrl()); }
    public String babelKey() { return value(BABEL_KEY, babeldoc.getOpenaiApiKey()); }
    public String babelModel() { return value(BABEL_MODEL, babeldoc.getOpenaiModel()); }
    public String zoteroUrl() { return value(ZOTERO_URL, zotero.getBaseUrl()); }
    public String zoteroKey() { return value(ZOTERO_KEY, zotero.getApiKey()); }
    public String zoteroUser() { return value(ZOTERO_USER, zotero.getUserId()); }

    public Map<String, Object> publicSettings() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("llm", provider("LLM / 通用生成", llmUrl(), llmModel(), llmKey()));
        data.put("babeldoc", provider("BabelDOC / PDF 翻译", babelUrl(), babelModel(), babelKey()));
        data.put("zotero", new LinkedHashMap<>(Map.of(
                "name", "Zotero 文献库", "baseUrl", zoteroUrl(), "userId", zoteroUser(),
                "configured", !zoteroKey().isBlank() && !zoteroUser().isBlank(),
                "apiKeyConfigured", !zoteroKey().isBlank(),
                "apiKeyHint", zoteroKey().isBlank() ? "未配置" : "已配置（" + zoteroKey().substring(Math.max(0, zoteroKey().length() - 4)) + "）")));
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
        saveSecret(LLM_KEY, llmBody.get("apiKey"), llmKey());
        save(BABEL_URL, url(string(babelBody, "baseUrl"), babelUrl()));
        save(BABEL_MODEL, text(string(babelBody, "model"), babelModel()));
        saveSecret(BABEL_KEY, babelBody.get("apiKey"), babelKey());
        save(ZOTERO_URL, url(string(zoteroBody, "baseUrl"), zoteroUrl()));
        save(ZOTERO_USER, text(string(zoteroBody, "userId"), zoteroUser()));
        saveSecret(ZOTERO_KEY, zoteroBody.get("apiKey"), zoteroKey());
        Map<String, Object> visibility = map(body.get("visibility"));
        VISIBILITY_DEFAULTS.forEach((feature, fallback) -> {
            String level = string(visibility, feature).toUpperCase();
            save("visibility." + feature, List.of("PUBLIC", "USER", "ROOT").contains(level) ? level : value("visibility." + feature, fallback));
        });
    }

    private Map<String, Object> provider(String name, String baseUrl, String model, String key) {
        return new LinkedHashMap<>(Map.of("name", name, "baseUrl", baseUrl, "model", model,
                "configured", !key.isBlank(), "apiKeyConfigured", !key.isBlank(),
                "apiKeyHint", key.isBlank() ? "未配置" : "已配置（" + key.substring(Math.max(0, key.length() - 4)) + "）"));
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
        if (!value.isBlank() && !value.startsWith("已配置（")) save(key, value);
        else if (current.isBlank()) save(key, "");
    }
    private String text(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private String url(String value, String fallback) { String result = text(value, fallback); try { URI uri = URI.create(result); if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) throw new IllegalArgumentException(); return result.replaceAll("/+$", ""); } catch (Exception e) { throw new AuthException(400, "API 地址必须是有效的 http/https URL"); } }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of(); }
    private String string(Map<String, Object> body, String key) { Object value = body.get(key); return value == null ? "" : value.toString().trim(); }
}
