package com.web.backen.zotero;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/zotero")
@CrossOrigin(origins = "*")
public class ZoteroController {

    private final ZoteroService zoteroService;

    public ZoteroController(ZoteroService zoteroService) {
        this.zoteroService = zoteroService;
    }

    /**
     * 文献列表（前端展示直接用这个）
     * 默认精简字段，避免把 Zotero 原始 JSON 全发给前端
     */
    @GetMapping("/items")
    public Map<String, Object> items(@RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> result = new HashMap<>();
        if (!zoteroService.isConfigured()) {
            result.put("code", 500);
            result.put("message", "Zotero 未配置：请设置 ZOTERO_API_KEY 和 ZOTERO_USER_ID");
            return result;
        }
        try {
            List<Map<String, Object>> raw = zoteroService.listItems(limit);
            List<Map<String, Object>> simplified = raw.stream().map(this::simplify).toList();
            result.put("code", 200);
            result.put("data", simplified);
            result.put("message", "success");
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "拉取失败：" + e.getMessage());
        }
        return result;
    }

    @GetMapping("/items/raw")
    public List<Map<String, Object>> itemsRaw(@RequestParam(defaultValue = "50") int limit) {
        return zoteroService.listItems(limit);
    }

    @GetMapping("/collections")
    public Map<String, Object> collections() {
        Map<String, Object> result = new HashMap<>();
        try {
            result.put("code", 200);
            result.put("data", zoteroService.listCollections());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> simplify(Map<String, Object> item) {
        Map<String, Object> data = (Map<String, Object>) item.getOrDefault("data", Map.of());
        Map<String, Object> out = new HashMap<>();
        out.put("key", item.get("key"));
        out.put("itemType", data.get("itemType"));
        out.put("title", data.get("title"));
        out.put("creators", data.get("creators"));
        out.put("date", data.get("date"));
        out.put("publicationTitle", data.get("publicationTitle"));
        out.put("DOI", data.get("DOI"));
        out.put("url", data.get("url"));
        out.put("abstractNote", data.get("abstractNote"));
        out.put("tags", data.get("tags"));
        out.put("collections", data.get("collections"));
        return out;
    }
}
