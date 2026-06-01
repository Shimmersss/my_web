package com.web.backen.zotero;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/zotero")
@CrossOrigin(origins = "*")
public class ZoteroController {

    private final ZoteroService zoteroService;

    public ZoteroController(ZoteroService zoteroService) {
        this.zoteroService = zoteroService;
    }

    /**
     * 文献列表：只返回母条目（过滤 attachment/note），并把 PDF 等附件挂到母条目下
     */
    @GetMapping("/items")
    @SuppressWarnings("unchecked")
    public Map<String, Object> items(@RequestParam(defaultValue = "200") int limit) {
        Map<String, Object> result = new HashMap<>();
        if (!zoteroService.isConfigured()) {
            result.put("code", 500);
            result.put("message", "Zotero 未配置：请设置 ZOTERO_API_KEY 和 ZOTERO_USER_ID");
            return result;
        }
        try {
            List<Map<String, Object>> raw = zoteroService.listItems(limit);

            // 1. 收集所有附件，按 parentItem 分组
            Map<String, List<Map<String, Object>>> attachByParent = new HashMap<>();
            for (Map<String, Object> it : raw) {
                Map<String, Object> data = (Map<String, Object>) it.getOrDefault("data", Map.of());
                if (!"attachment".equals(data.get("itemType"))) continue;
                Object parent = data.get("parentItem");
                if (parent == null) continue;
                Map<String, Object> a = new HashMap<>();
                a.put("key", it.get("key"));
                a.put("filename", data.get("filename"));
                a.put("title", data.get("title"));
                a.put("contentType", data.get("contentType"));
                a.put("isPdf", "application/pdf".equals(data.get("contentType")));
                attachByParent.computeIfAbsent(parent.toString(), k -> new ArrayList<>()).add(a);
            }

            // 2. 母条目 + 挂上 attachments
            List<Map<String, Object>> simplified = raw.stream()
                    .filter(it -> {
                        Map<String, Object> d = (Map<String, Object>) it.getOrDefault("data", Map.of());
                        String t = (String) d.get("itemType");
                        return !"attachment".equals(t) && !"note".equals(t);
                    })
                    .map(it -> simplify(it, attachByParent))
                    .collect(Collectors.toList());

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

    /**
     * PDF / 附件文件代理，inline 显示
     */
    @GetMapping("/file/{key}")
    public ResponseEntity<byte[]> file(@PathVariable String key) {
        try {
            ResponseEntity<byte[]> upstream = zoteroService.fetchItemFile(key);
            HttpHeaders out = new HttpHeaders();
            MediaType ct = upstream.getHeaders().getContentType();
            out.setContentType(ct != null ? ct : MediaType.APPLICATION_PDF);
            out.set(HttpHeaders.CONTENT_DISPOSITION, "inline");
            out.setCacheControl("private, max-age=3600");
            return new ResponseEntity<>(upstream.getBody(), out, upstream.getStatusCode());
        } catch (Exception e) {
            return ResponseEntity.status(502).body(("file proxy error: " + e.getMessage()).getBytes());
        }
    }

    /**
     * 引用导出
     * format: bibtex | ris | bibliography
     * style:  bibliography 模式下的 CSL 样式名（默认 apa）
     */
    @GetMapping(value = "/items/{key}/export", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> export(@PathVariable String key,
                                         @RequestParam(defaultValue = "bibtex") String format,
                                         @RequestParam(defaultValue = "apa") String style) {
        try {
            String body = zoteroService.exportItem(key, format, style);
            HttpHeaders headers = new HttpHeaders();
            if ("bibtex".equals(format)) {
                headers.setContentType(MediaType.parseMediaType("application/x-bibtex; charset=UTF-8"));
            } else if ("ris".equals(format)) {
                headers.setContentType(MediaType.parseMediaType("application/x-research-info-systems; charset=UTF-8"));
            } else {
                headers.setContentType(MediaType.parseMediaType("text/html; charset=UTF-8"));
            }
            return new ResponseEntity<>(body, headers, 200);
        } catch (Exception e) {
            return ResponseEntity.status(502).body("export error: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> simplify(Map<String, Object> item,
                                         Map<String, List<Map<String, Object>>> attachByParent) {
        Map<String, Object> data = (Map<String, Object>) item.getOrDefault("data", Map.of());
        Map<String, Object> out = new HashMap<>();
        String key = (String) item.get("key");
        out.put("key", key);
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
        out.put("attachments", attachByParent.getOrDefault(key, List.of()));
        return out;
    }
}
