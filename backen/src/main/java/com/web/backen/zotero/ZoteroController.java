package com.web.backen.zotero;

import org.springframework.http.HttpHeaders;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/zotero")
@CrossOrigin(origins = "*")
public class ZoteroController {

    private final ZoteroService zoteroService;
    private final ZoteroCache zoteroCache;

    public ZoteroController(ZoteroService zoteroService, ZoteroCache zoteroCache) {
        this.zoteroService = zoteroService;
        this.zoteroCache = zoteroCache;
    }

    /**
     * 文献列表（命中内存缓存，毫秒级返回）
     * ?refresh=true 触发后台刷新（不阻塞当前请求）
     */
    @GetMapping("/items")
    public Map<String, Object> items(@RequestParam(required = false) Boolean refresh) {
        Map<String, Object> result = new HashMap<>();
        if (!zoteroService.isConfigured()) {
            result.put("code", 500);
            result.put("message", "Zotero 未配置：请设置 ZOTERO_API_KEY 和 ZOTERO_USER_ID");
            return result;
        }
        if (Boolean.TRUE.equals(refresh)) {
            zoteroCache.warmAsync();
        }
        result.put("code", 200);
        result.put("data", zoteroCache.getItems());
        result.put("updatedAt", zoteroCache.getItemsUpdatedAt());
        result.put("warmedUp", zoteroCache.isWarmedUp());
        result.put("message", "success");
        return result;
    }

    @GetMapping("/items/raw")
    public List<Map<String, Object>> itemsRaw(@RequestParam(defaultValue = "200") int limit) {
        return zoteroService.listItems(limit);
    }

    @GetMapping("/collections")
    public Map<String, Object> collections() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", zoteroCache.getCollections());
        result.put("updatedAt", zoteroCache.getCollectionsUpdatedAt());
        return result;
    }

    /**
     * PDF / 附件文件代理，inline 显示
     */
    @GetMapping("/file/{key}")
    public ResponseEntity<?> file(@PathVariable String key) {
        try {
            ZoteroService.ProxiedFile upstream = zoteroService.fetchItemFile(key);
            HttpHeaders out = new HttpHeaders();
            out.setContentType(upstream.contentType());
            out.set(HttpHeaders.CONTENT_DISPOSITION, "inline");
            out.setCacheControl("private, max-age=3600");
            out.set("X-Accel-Buffering", "no");
            if (upstream.contentLength() >= 0) {
                out.setContentLength(upstream.contentLength());
            }
            return new ResponseEntity<>(new InputStreamResource(upstream.body()), out, upstream.statusCode());
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
}
