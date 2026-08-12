package com.web.backen.zotero;

import com.web.backen.auth.AuthService;
import com.web.backen.auth.RuntimeConfigService;
import org.springframework.http.HttpHeaders;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

@RestController
@RequestMapping("/api/zotero")
public class ZoteroController {
    private static final Logger log = LoggerFactory.getLogger(ZoteroController.class);

    private final ZoteroService zoteroService;
    private final ZoteroCache zoteroCache;
    private final AuthService authService;
    private final RuntimeConfigService runtimeConfig;

    public ZoteroController(ZoteroService zoteroService, ZoteroCache zoteroCache, AuthService authService,
                            RuntimeConfigService runtimeConfig) {
        this.zoteroService = zoteroService;
        this.zoteroCache = zoteroCache;
        this.authService = authService;
        this.runtimeConfig = runtimeConfig;
    }

    /**
     * 文献列表（命中内存缓存，毫秒级返回）
     * ?refresh=true 触发后台刷新（不阻塞当前请求）
     */
    @GetMapping("/items")
    public Map<String, Object> items(@RequestParam(required = false) Boolean refresh) {
        requirePublicationAccess();
        Map<String, Object> result = new HashMap<>();
        if (!zoteroService.isConfigured()) {
            result.put("code", 500);
            result.put("message", "Zotero 未配置：请设置 ZOTERO_API_KEY 和 ZOTERO_USER_ID");
            return result;
        }
        if (Boolean.TRUE.equals(refresh)) {
            zoteroCache.refreshAsync();
        }
        result.put("code", 200);
        result.put("data", zoteroCache.getItems());
        result.put("updatedAt", zoteroCache.getItemsUpdatedAt());
        result.put("warmedUp", zoteroCache.isWarmedUp());
        result.put("refreshing", zoteroCache.isRefreshing());
        if (zoteroCache.getLastError() != null) result.put("syncWarning", zoteroCache.getLastError());
        result.put("message", "success");
        return result;
    }

    @GetMapping("/items/raw")
    public List<Map<String, Object>> itemsRaw(@RequestParam(defaultValue = "200") int limit) {
        authService.requireRoot(request());
        return zoteroService.listItems(limit);
    }

    @GetMapping("/collections")
    public Map<String, Object> collections(@RequestParam(required = false) Boolean refresh) {
        requirePublicationAccess();
        Map<String, Object> result = new HashMap<>();
        if (Boolean.TRUE.equals(refresh) && zoteroService.isConfigured()) {
            zoteroCache.refreshAsync();
        }
        result.put("code", 200);
        result.put("data", zoteroCache.getCollections());
        result.put("updatedAt", zoteroCache.getCollectionsUpdatedAt());
        result.put("warmedUp", zoteroCache.isWarmedUp());
        result.put("refreshing", zoteroCache.isRefreshing());
        if (zoteroCache.getLastError() != null) result.put("syncWarning", zoteroCache.getLastError());
        return result;
    }

    /**
     * PDF / 附件文件代理，inline 显示
     */
    @GetMapping("/file/{key}")
    public ResponseEntity<?> file(@PathVariable String key) {
        requirePublicationAccess();
        if (!isValidKey(key)) {
            return ResponseEntity.badRequest().body("附件标识无效".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
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
            log.error("Zotero 附件代理失败: itemKey={}", key, e);
            return ResponseEntity.status(502).body("附件暂时无法读取".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        requirePublicationAccess();
        if (!isValidKey(key)) return ResponseEntity.badRequest().body("条目标识无效");
        if (style == null || !style.matches("[A-Za-z0-9._-]{1,80}")) {
            return ResponseEntity.badRequest().body("引用样式无效");
        }
        try {
            if (!List.of("bibtex", "ris", "bibliography").contains(format)) {
                return ResponseEntity.badRequest().body("导出格式无效");
            }
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
            log.error("Zotero 引用导出失败: itemKey={}, format={}", key, format, e);
            return ResponseEntity.status(502).body("引用暂时无法导出");
        }
    }

    private jakarta.servlet.http.HttpServletRequest request() {
        return ((org.springframework.web.context.request.ServletRequestAttributes)
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()).getRequest();
    }

    private void requirePublicationAccess() {
        String level = runtimeConfig.visibilityLevel("Publications");
        if ("ROOT".equals(level)) authService.requireRoot(request());
        else if ("USER".equals(level)) authService.requireUser(request());
    }

    private boolean isValidKey(String key) {
        return key != null && key.matches("[A-Za-z0-9]{8}");
    }
}
