package com.web.backen.zotero;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A complete, internally consistent Zotero library snapshot served only from memory. */
@Component
public class ZoteroCache {
    private static final Logger log = LoggerFactory.getLogger(ZoteroCache.class);

    private final ZoteroService zoteroService;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());
    private final AtomicBoolean refreshQueued = new AtomicBoolean(false);
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "zotero-cache-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean refreshing;
    private volatile String lastError;

    public ZoteroCache(ZoteroService zoteroService) { this.zoteroService = zoteroService; }

    @PostConstruct
    public void init() {
        if (!zoteroService.isConfigured()) {
            lastError = "Zotero 未配置";
            log.warn("Zotero 未配置，跳过缓存预热");
            return;
        }
        warmAsync();
    }

    @PreDestroy
    void shutdown() { refreshExecutor.shutdownNow(); }

    /** Coalesces manual, visibility and scheduled refresh requests into one upstream request. */
    public void warmAsync() {
        enqueueRefresh(false);
    }

    /** Explicit user/admin refresh bypasses the freshness window but still coalesces concurrent requests. */
    public void refreshAsync() {
        enqueueRefresh(true);
    }

    private void enqueueRefresh(boolean force) {
        Snapshot current = snapshot.get();
        if (!zoteroService.isConfigured()
                || (!force && current.warmedUp() && System.currentTimeMillis() - current.updatedAt() < 60_000)
                || !refreshQueued.compareAndSet(false, true)) return;
        refreshExecutor.execute(() -> {
            try { refresh(); }
            finally { refreshQueued.set(false); }
        });
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void scheduledRefresh() { warmAsync(); }

    /** Fetches both resources first and publishes them atomically; partial failures never mix generations. */
    public synchronized void refresh() {
        long started = System.currentTimeMillis();
        refreshing = true;
        try {
            List<Map<String, Object>> rawCollections = zoteroService.listCollections();
            List<Map<String, Object>> collections = normalizeCollections(rawCollections);
            Set<String> activeCollectionKeys = new HashSet<>();
            collections.forEach(collection -> activeCollectionKeys.add(String.valueOf(collection.get("key"))));
            List<Map<String, Object>> items = processItems(zoteroService.listAllItems(), activeCollectionKeys);
            long updatedAt = System.currentTimeMillis();
            snapshot.set(new Snapshot(items, collections, updatedAt, true));
            lastError = null;
            log.info("Zotero 缓存原子刷新完成: items={}, collections={}, elapsedMs={}",
                    items.size(), collections.size(), updatedAt - started);
        } catch (Exception e) {
            lastError = "同步失败，请稍后重试";
            log.error("Zotero 缓存刷新失败，继续使用上一份完整快照", e);
        } finally {
            refreshing = false;
        }
    }

    public List<Map<String, Object>> getItems() { return snapshot.get().items(); }
    public List<Map<String, Object>> getCollections() { return snapshot.get().collections(); }
    public long getItemsUpdatedAt() { return snapshot.get().updatedAt(); }
    public long getCollectionsUpdatedAt() { return snapshot.get().updatedAt(); }
    public boolean isWarmedUp() { return snapshot.get().warmedUp(); }
    public boolean isRefreshing() { return refreshing || refreshQueued.get(); }
    public String getLastError() { return lastError; }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> normalizeCollections(List<Map<String, Object>> raw) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> wrapper : safeList(raw)) {
            Map<String, Object> data = wrapper.get("data") instanceof Map<?, ?> value
                    ? (Map<String, Object>) value : wrapper;
            if (truthy(data.get("deleted")) || truthy(wrapper.get("deleted"))) continue;
            String key = text(wrapper.get("key"), text(data.get("key"), ""));
            if (key.isBlank()) continue;
            String name = text(data.get("name"), "未命名分组");
            Object parentValue = data.get("parentCollection");
            String parent = parentValue instanceof String value && !value.isBlank() ? value : null;
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("key", key);
            normalized.put("name", name);
            normalized.put("parentCollection", parent);
            normalized.put("version", number(wrapper.get("version"), number(data.get("version"), 0)));
            byKey.put(key, normalized);
        }
        Set<String> keys = byKey.keySet();
        byKey.values().forEach(collection -> {
            String parent = (String) collection.get("parentCollection");
            if (parent != null && !keys.contains(parent)) collection.put("parentCollection", null);
        });
        return List.copyOf(byKey.values());
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> processItems(List<Map<String, Object>> raw, Set<String> activeCollectionKeys) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> item : safeList(raw)) {
            String key = text(item.get("key"), "");
            if (!key.isBlank()) unique.put(key, item);
        }

        Map<String, List<Map<String, Object>>> attachmentsByParent = new HashMap<>();
        for (Map<String, Object> item : unique.values()) {
            Map<String, Object> data = data(item);
            if (truthy(data.get("deleted")) || !"attachment".equals(text(data.get("itemType"), ""))) continue;
            String parent = parentKey(data.get("parentItem"));
            if (parent != null) attachmentsByParent.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(attachment(item, data));
        }
        attachmentsByParent.values().forEach(list -> list.sort(Comparator
                .comparing((Map<String, Object> value) -> !Boolean.TRUE.equals(value.get("isPdf")))
                .thenComparing(value -> text(value.get("filename"), text(value.get("title"), "")), String.CASE_INSENSITIVE_ORDER)));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : unique.values()) {
            Map<String, Object> data = data(item);
            if (truthy(data.get("deleted"))) continue;
            String type = text(data.get("itemType"), "");
            if ("note".equals(type)) continue;
            String parent = parentKey(data.get("parentItem"));
            if ("attachment".equals(type) && parent != null) continue;
            result.add(simplify(item, data, attachmentsByParent, activeCollectionKeys));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> simplify(Map<String, Object> item, Map<String, Object> data,
                                         Map<String, List<Map<String, Object>>> attachmentsByParent,
                                         Set<String> activeCollectionKeys) {
        String key = text(item.get("key"), "");
        String type = text(data.get("itemType"), "document");
        String title = text(data.get("title"), text(data.get("filename"), ""));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key); out.put("version", number(item.get("version"), number(data.get("version"), 0)));
        out.put("itemType", type); out.put("title", title); out.put("creators", list(data.get("creators")));
        out.put("date", text(data.get("date"), "")); out.put("publicationTitle", text(data.get("publicationTitle"), ""));
        out.put("DOI", text(data.get("DOI"), "")); out.put("url", text(data.get("url"), ""));
        out.put("abstractNote", text(data.get("abstractNote"), "")); out.put("tags", list(data.get("tags")));
        out.put("collections", stringList(data.get("collections")).stream().filter(activeCollectionKeys::contains).toList());
        if ("attachment".equals(type)) out.put("attachments", List.of(attachment(item, data)));
        else out.put("attachments", List.copyOf(attachmentsByParent.getOrDefault(key, List.of())));
        return out;
    }

    private Map<String, Object> attachment(Map<String, Object> item, Map<String, Object> data) {
        String contentType = text(data.get("contentType"), "application/octet-stream");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("key", text(item.get("key"), "")); value.put("filename", text(data.get("filename"), ""));
        value.put("title", text(data.get("title"), "")); value.put("contentType", contentType);
        value.put("isPdf", "application/pdf".equalsIgnoreCase(contentType)
                || text(data.get("filename"), "").toLowerCase(Locale.ROOT).endsWith(".pdf"));
        return value;
    }

    @SuppressWarnings("unchecked") private Map<String, Object> data(Map<String, Object> item) { return item.get("data") instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of(); }
    private String parentKey(Object value) { return value instanceof String text && !text.isBlank() ? text : null; }
    private boolean truthy(Object value) { return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value)); }
    private int number(Object value, int fallback) { try { return value == null ? fallback : ((Number) value).intValue(); } catch (Exception e) { return fallback; } }
    private String text(Object value, String fallback) { return value == null || value.toString().isBlank() ? fallback : value.toString().trim(); }
    private List<?> list(Object value) { return value instanceof List<?> list ? List.copyOf(list) : List.of(); }
    private List<String> stringList(Object value) { return value instanceof List<?> list ? list.stream().map(String::valueOf).filter(s -> !s.isBlank()).distinct().toList() : List.of(); }
    private List<Map<String, Object>> safeList(List<Map<String, Object>> value) { return value == null ? List.of() : value; }

    private record Snapshot(List<Map<String, Object>> items, List<Map<String, Object>> collections,
                            long updatedAt, boolean warmedUp) {
        private static Snapshot empty() { return new Snapshot(List.of(), List.of(), 0, false); }
    }
}
