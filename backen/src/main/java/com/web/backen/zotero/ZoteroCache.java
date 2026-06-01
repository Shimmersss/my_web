package com.web.backen.zotero;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Zotero 数据缓存：
 * - items（已处理：母条目 + attachments 挂载）
 * - collections（原始）
 *
 * 启动后异步预热，每 5 分钟静默刷新。
 * 用户请求 100% 命中内存，避免 Zotero API 16-30 秒的尾延迟。
 */
@Component
public class ZoteroCache {

    private static final Logger log = LoggerFactory.getLogger(ZoteroCache.class);

    private final ZoteroService zoteroService;

    private final AtomicReference<List<Map<String, Object>>> itemsRef = new AtomicReference<>(List.of());
    private final AtomicReference<List<Map<String, Object>>> collectionsRef = new AtomicReference<>(List.of());
    private volatile long itemsUpdatedAt = 0;
    private volatile long collectionsUpdatedAt = 0;
    private volatile boolean warmedUp = false;

    public ZoteroCache(ZoteroService zoteroService) {
        this.zoteroService = zoteroService;
    }

    @PostConstruct
    public void init() {
        if (!zoteroService.isConfigured()) {
            log.warn("Zotero 未配置，跳过缓存预热");
            return;
        }
        warmAsync();
    }

    @Async
    public void warmAsync() {
        new Thread(this::refresh, "zotero-cache-warm").start();
    }

    /** 每 5 分钟静默刷新一次 */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void scheduledRefresh() {
        if (!zoteroService.isConfigured()) return;
        refresh();
    }

    public synchronized void refresh() {
        long t0 = System.currentTimeMillis();
        try {
            List<Map<String, Object>> rawItems = zoteroService.listItems(200);
            itemsRef.set(processItems(rawItems));
            itemsUpdatedAt = System.currentTimeMillis();
            log.info("Zotero items 缓存刷新完成，{} 条母条目，耗时 {} ms",
                    itemsRef.get().size(), itemsUpdatedAt - t0);
        } catch (Exception e) {
            log.error("Zotero items 缓存刷新失败: {}", e.getMessage());
        }

        long t1 = System.currentTimeMillis();
        try {
            collectionsRef.set(zoteroService.listCollections());
            collectionsUpdatedAt = System.currentTimeMillis();
            log.info("Zotero collections 缓存刷新完成，{} 条，耗时 {} ms",
                    collectionsRef.get().size(), collectionsUpdatedAt - t1);
        } catch (Exception e) {
            log.error("Zotero collections 缓存刷新失败: {}", e.getMessage());
        }

        warmedUp = true;
    }

    public List<Map<String, Object>> getItems() {
        return itemsRef.get();
    }

    public List<Map<String, Object>> getCollections() {
        return collectionsRef.get();
    }

    public long getItemsUpdatedAt() {
        return itemsUpdatedAt;
    }

    public long getCollectionsUpdatedAt() {
        return collectionsUpdatedAt;
    }

    public boolean isWarmedUp() {
        return warmedUp;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> processItems(List<Map<String, Object>> raw) {
        // 1. 收集"作为子附件"的 attachment（有 parentItem），按 parentItem 分组挂载
        //    parentItem=null 的孤立附件（用户直接上传的 md / pdf 等）则保留为独立条目
        Map<String, List<Map<String, Object>>> attachByParent = new HashMap<>();
        for (Map<String, Object> it : raw) {
            Map<String, Object> data = (Map<String, Object>) it.getOrDefault("data", Map.of());
            if (!"attachment".equals(data.get("itemType"))) continue;
            Object parent = data.get("parentItem");
            if (parent == null) continue; // 孤立附件交给主流程作为独立条目
            Map<String, Object> a = new HashMap<>();
            a.put("key", it.get("key"));
            a.put("filename", data.get("filename"));
            a.put("title", data.get("title"));
            a.put("contentType", data.get("contentType"));
            a.put("isPdf", "application/pdf".equals(data.get("contentType")));
            attachByParent.computeIfAbsent(parent.toString(), k -> new ArrayList<>()).add(a);
        }

        // 2. 主条目 = 非附件/非笔记 + 孤立附件（parentItem=null 的 attachment）
        return raw.stream()
                .filter(it -> {
                    Map<String, Object> d = (Map<String, Object>) it.getOrDefault("data", Map.of());
                    String t = (String) d.get("itemType");
                    if ("note".equals(t)) return false;
                    if ("attachment".equals(t)) {
                        // 只保留孤立附件
                        return d.get("parentItem") == null;
                    }
                    return true;
                })
                .map(it -> simplify(it, attachByParent))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> simplify(Map<String, Object> item,
                                         Map<String, List<Map<String, Object>>> attachByParent) {
        Map<String, Object> data = (Map<String, Object>) item.getOrDefault("data", Map.of());
        Map<String, Object> out = new HashMap<>();
        String key = (String) item.get("key");
        String type = (String) data.get("itemType");
        out.put("key", key);
        out.put("itemType", type);
        // 孤立 attachment 没有 title 时用 filename 兜底，避免出现 "(无标题)"
        Object title = data.get("title");
        if ((title == null || title.toString().isBlank()) && data.get("filename") != null) {
            title = data.get("filename");
        }
        out.put("title", title);
        out.put("creators", data.get("creators"));
        out.put("date", data.get("date"));
        out.put("publicationTitle", data.get("publicationTitle"));
        out.put("DOI", data.get("DOI"));
        out.put("url", data.get("url"));
        out.put("abstractNote", data.get("abstractNote"));
        out.put("tags", data.get("tags"));
        out.put("collections", data.get("collections"));

        // 孤立附件：把自己作为附件挂到自己下面，让前端 "查看附件" 按钮可用
        if ("attachment".equals(type) && data.get("parentItem") == null) {
            Map<String, Object> self = new HashMap<>();
            self.put("key", key);
            self.put("filename", data.get("filename"));
            self.put("title", data.get("title"));
            self.put("contentType", data.get("contentType"));
            self.put("isPdf", "application/pdf".equals(data.get("contentType")));
            out.put("attachments", List.of(self));
        } else {
            out.put("attachments", attachByParent.getOrDefault(key, List.of()));
        }
        return out;
    }
}
