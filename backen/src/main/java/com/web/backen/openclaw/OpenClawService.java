package com.web.backen.openclaw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.web.backen.config.OpenClawConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class OpenClawService {

    private final OpenClawConfig config;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> inFlight = new ConcurrentHashMap<>();
    private final Semaphore cliSlots;

    public OpenClawService(OpenClawConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.cliSlots = new Semaphore(Math.max(1, config.getMaxConcurrentCalls()));
    }

    @PostConstruct
    public void warmStaticCaches() {
        CompletableFuture.runAsync(() -> {
            try {
                models();
                commands();
                sessions();
            } catch (Exception ignored) {
                // Gateway may not be ready when Spring starts; the first request will retry.
            }
        });
    }

    public JsonNode history(String sessionKey) {
        String resolvedSessionKey = sessionKey(sessionKey);
        return cached("history:" + resolvedSessionKey, config.getHistoryCacheMillis(), () -> call("chat.history", Map.of(
                "sessionKey", resolvedSessionKey,
                "limit", 100,
                "maxChars", 20000
        )));
    }

    public JsonNode send(String sessionKey, String message, List<Map<String, Object>> attachments) {
        Map<String, Object> params = new HashMap<>();
        params.put("sessionKey", sessionKey(sessionKey));
        params.put("message", message);
        params.put("idempotencyKey", UUID.randomUUID().toString());
        if (!attachments.isEmpty()) {
            params.put("attachments", attachments);
        }
        JsonNode result = call("chat.send", params);
        invalidateHistory(sessionKey);
        invalidateSessions();
        return result;
    }

    public JsonNode sessions() {
        return cached("sessions", config.getSessionsCacheMillis(), () -> call("sessions.list", Map.of(
                "limit", 100,
                "includeDerivedTitles", true,
                "includeLastMessage", true,
                "includeGlobal", true,
                "includeUnknown", true
        )));
    }

    public JsonNode createSession(String label, String model) {
        Map<String, Object> params = new HashMap<>();
        putIfPresent(params, "label", label);
        putIfPresent(params, "model", model);
        JsonNode result = call("sessions.create", params);
        invalidateSessions();
        return result;
    }

    public JsonNode resetSession(String sessionKey) {
        JsonNode result = call("sessions.reset", Map.of(
                "key", requiredSessionKey(sessionKey),
                "reason", "new"
        ));
        invalidateHistory(sessionKey);
        invalidateSessions();
        return result;
    }

    public JsonNode patchSession(String sessionKey, String label, String model, String thinkingLevel) {
        Map<String, Object> params = new HashMap<>();
        params.put("key", requiredSessionKey(sessionKey));
        putIfPresent(params, "label", label);
        putIfPresent(params, "model", model);
        putIfPresent(params, "thinkingLevel", thinkingLevel);
        if (params.size() == 1) {
            throw new IllegalArgumentException("至少需要修改一个会话设置");
        }
        JsonNode result = call("sessions.patch", params);
        invalidateSessions();
        return result;
    }

    public JsonNode models() {
        return cached("models", config.getModelsCacheMillis(), () -> {
            JsonNode catalog = call("models.list", Map.of("view", "all"));
            JsonNode openClawConfig = call("config.get", Map.of()).path("config");
            Set<String> configuredModels = configuredModels(openClawConfig);
            ArrayNode models = objectMapper.createArrayNode();
            for (JsonNode model : catalog.path("models")) {
                String key = model.path("provider").asText() + "/" + model.path("id").asText();
                if (configuredModels.contains(key)) {
                    models.add(model);
                }
            }
            ObjectNode result = objectMapper.createObjectNode();
            result.set("models", models);
            return result;
        });
    }

    public JsonNode commands() {
        return cached("commands", config.getCommandsCacheMillis(),
                () -> call("commands.list", Map.of("includeArgs", true)));
    }

    public JsonNode artifacts(String sessionKey) {
        return call("artifacts.list", Map.of("sessionKey", requiredSessionKey(sessionKey)));
    }

    public JsonNode downloadArtifact(String sessionKey, String artifactId) {
        return call("artifacts.download", Map.of(
                "sessionKey", requiredSessionKey(sessionKey),
                "artifactId", artifactId
        ));
    }

    private String sessionKey(String sessionKey) {
        return sessionKey == null || sessionKey.isBlank() ? config.getSessionKey() : sessionKey.trim();
    }

    private String requiredSessionKey(String sessionKey) {
        String resolved = sessionKey(sessionKey);
        if (resolved.isBlank()) {
            throw new IllegalArgumentException("会话 key 不能为空");
        }
        return resolved;
    }

    private void putIfPresent(Map<String, Object> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.put(key, value.trim());
        }
    }

    private Set<String> configuredModels(JsonNode openClawConfig) {
        Set<String> models = new HashSet<>();
        JsonNode providers = openClawConfig.path("models").path("providers");
        providers.fields().forEachRemaining(provider -> {
            for (JsonNode model : provider.getValue().path("models")) {
                String id = model.path("id").asText("");
                if (!id.isBlank()) {
                    models.add(provider.getKey() + "/" + id);
                }
            }
        });
        return models;
    }

    private JsonNode call(String method, Map<String, Object> params) {
        boolean acquired = false;
        try {
            acquired = cliSlots.tryAcquire(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException("OpenClaw CLI 调用繁忙，请稍后重试");
            }
            String paramsJson = objectMapper.writeValueAsString(params);
            Process process = new ProcessBuilder(List.of(
                    config.getCommand(),
                    "gateway",
                    "call",
                    method,
                    "--json",
                    "--params",
                    paramsJson
            )).redirectErrorStream(true).start();

            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            boolean completed = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("OpenClaw Gateway 响应超时");
            }

            String output = outputFuture.get();
            if (process.exitValue() != 0) {
                throw new IllegalStateException(output.isBlank() ? "OpenClaw Gateway 调用失败" : output);
            }
            return objectMapper.readTree(output);
        } catch (IOException e) {
            throw new IllegalStateException("无法执行 OpenClaw CLI，请确认已安装并可从 PATH 访问", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenClaw Gateway 调用被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("读取 OpenClaw Gateway 响应失败", e);
        } finally {
            if (acquired) {
                cliSlots.release();
            }
        }
    }

    private JsonNode cached(String key, int ttlMillis, Supplier<JsonNode> loader) {
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.expiresAt() > now) {
            return entry.value();
        }

        CompletableFuture<JsonNode> created = new CompletableFuture<>();
        CompletableFuture<JsonNode> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            try {
                return existing.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("OpenClaw 缓存等待被中断", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause() != null ? e.getCause().getMessage() : "OpenClaw 调用失败", e);
            }
        }

        try {
            JsonNode value = loader.get();
            cache.put(key, new CacheEntry(value, System.currentTimeMillis() + Math.max(0, ttlMillis)));
            created.complete(value);
            return value;
        } catch (RuntimeException e) {
            created.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, created);
        }
    }

    private void invalidateHistory(String sessionKey) {
        cache.remove("history:" + sessionKey(sessionKey));
    }

    private void invalidateSessions() {
        cache.remove("sessions");
    }

    private record CacheEntry(JsonNode value, long expiresAt) {}
}
