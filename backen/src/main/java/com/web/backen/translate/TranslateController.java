package com.web.backen.translate;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/translate")
public class TranslateController {

    private static final Logger log = LoggerFactory.getLogger(TranslateController.class);

    private final TranslationService translationService;
    private final AuthService authService;
    private final QuotaService quotaService;

    public TranslateController(TranslationService translationService, AuthService authService, QuotaService quotaService) {
        this.translationService = translationService;
        this.authService = authService;
        this.quotaService = quotaService;
    }

    /**
     * 上传 PDF 文件，获取页数信息（不立即翻译）
     */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        AuthUser user;
        try {
            authService.requireCsrf(request);
            user = authService.requireUser(request);
        } catch (AuthException e) {
            return authError(e);
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "请上传 PDF 文件"));
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "仅支持 PDF 文件格式"));
        }

        if (file.getSize() > 50 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "文件大小超过 50MB 限制"));
        }

        try {
            TranslationSession session = translationService.createSessionPreview(fileName, file.getInputStream(), user.id());

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", Map.of(
                            "taskId", session.getTaskId(),
                            "fileName", session.getFileName(),
                            "totalPages", session.getTotalPages(),
                            "textQualitySuspicious", session.isTextQualitySuspicious(),
                            "textQualityWarning", session.getTextQualityWarning() != null ? session.getTextQualityWarning() : ""
                    )
            ));
        } catch (IllegalArgumentException e) {
            log.warn("PDF 解析失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("上传处理失败", e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "PDF 处理失败: " + e.getMessage()));
        }
    }

    /**
     * 开始翻译：指定页面范围并交给 BabelDOC
     */
    @PostMapping("/start/{taskId}")
    public ResponseEntity<?> start(@PathVariable String taskId,
                                    @RequestParam(defaultValue = "1") int startPage,
                                    @RequestParam(required = false) Integer endPage,
                                    @RequestParam(defaultValue = "auto") String fontFamily,
                                    @RequestParam(defaultValue = "4") int qps,
                                    HttpServletRequest request) {
        AuthUser user;
        try {
            authService.requireCsrf(request);
            user = authService.requireUser(request);
        } catch (AuthException e) {
            return authError(e);
        }
        try {
            TranslationSession session = translationService.startTranslation(
                    taskId, startPage, endPage != null ? endPage : Integer.MAX_VALUE, fontFamily, qps, user);

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", Map.of(
                            "taskId", session.getTaskId(),
                            "pageCount", session.getEndPage() - session.getStartPage() + 1,
                            "status", session.getStatus(),
                            "qps", session.getQps(),
                            "requestedQps", session.getRequestedQps(),
                            "creditCost", session.getCreditCost(),
                            "credits", quotaService.balance(user.id()),
                            "resourceDowngraded", session.isResourceDowngraded(),
                            "queuePosition", session.getQueuePosition()
                    )
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(429).body(Map.of("code", 429, "message", e.getMessage()));
        } catch (AuthException e) {
            return authError(e);
        } catch (Exception e) {
            log.error("开始翻译失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "处理失败: " + e.getMessage()));
        }
    }

    /**
     * SSE 流式推送翻译进度
     */
    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId, HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(6 * 60 * 60 * 1000L); // 长篇论文翻译可能持续数小时
        AuthUser user = authService.currentUser(request).orElse(null);
        TranslationSession session = translationService.getSession(taskId);
        if (!translationService.canAccess(session, user)) {
            try {
                emitter.send(SseEmitter.event().name("task-error").data(Map.of("message", "任务不存在")));
            } catch (Exception ignored) {}
            emitter.complete();
            return emitter;
        }

        emitter.onTimeout(() -> {
            log.warn("SSE 超时: taskId={}", taskId);
            emitter.complete();
        });

        emitter.onError(e -> {
            log.warn("SSE 错误: taskId={}", taskId, e);
        });

        translationService.subscribe(taskId, emitter);
        return emitter;
    }

    /**
     * 查询翻译任务状态（用于断线重连）
     */
    @GetMapping("/status/{taskId}")
    public ResponseEntity<?> status(@PathVariable String taskId, HttpServletRequest request) {
        AuthUser user;
        try {
            user = authService.requireUser(request);
        } catch (AuthException e) {
            return authError(e);
        }
        TranslationSession session = translationService.getSession(taskId);
        if (!translationService.canAccess(session, user)) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "任务不存在"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", session.getTaskId());
        data.put("fileName", session.getFileName());
        data.put("status", session.getStatus());
        data.put("textQualitySuspicious", session.isTextQualitySuspicious());
        data.put("textQualityWarning", session.getTextQualityWarning() != null ? session.getTextQualityWarning() : "");
        data.put("totalPages", session.getTotalPages());
        data.put("startPage", session.getStartPage());
        data.put("endPage", session.getEndPage());
        data.put("fontFamily", session.getFontFamily());
        data.put("qps", session.getQps());
        data.put("requestedQps", session.getRequestedQps());
        data.put("resourceDowngraded", session.isResourceDowngraded());
        data.put("resourceDowngradeReason", session.getResourceDowngradeReason() != null ? session.getResourceDowngradeReason() : "");
        data.put("resourceDowngradeCount", session.getResourceDowngradeCount());
        data.put("progress", session.getProgress());
        data.put("progressStage", session.getProgressStage());
        data.put("progressStageLabel", translationService.stageLabel(session.getProgressStage()));
        data.put("errorMessage", session.getErrorMessage() != null ? session.getErrorMessage() : "");
        data.put("queuePosition", session.getQueuePosition());
        data.put("creditCost", session.getCreditCost());
        data.put("credits", quotaService.balance(user.id()));
        data.put("createdAt", session.getCreatedAt());
        data.put("updatedAt", session.getUpdatedAt());
        data.put("completedAt", session.getCompletedAt());
        return ResponseEntity.ok(Map.of("code", 200, "data", data));
    }

    /**
     * 最近翻译任务。只返回轻量元数据，PDF 文件通过下载接口按需读取。
     */
    @GetMapping("/recent")
    public ResponseEntity<?> recent(HttpServletRequest request) {
        AuthUser user;
        try {
            user = authService.requireUser(request);
        } catch (AuthException e) {
            return authError(e);
        }
        List<Map<String, Object>> data = translationService.getRecentSessions(user).stream()
                .map(this::toSummary)
                .toList();
        return ResponseEntity.ok(Map.of("code", 200, "data", data));
    }

    /**
     * 下载翻译结果（TXT）
     */
    @GetMapping("/download/{taskId}")
    public ResponseEntity<?> download(@PathVariable String taskId, HttpServletRequest request) {
        try {
            AuthUser user = authService.requireUser(request);
            TranslationSession session = translationService.getSession(taskId);
            if (!translationService.canAccess(session, user)) {
                return ResponseEntity.status(404).body(Map.of("code", 404, "message", "任务不存在"));
            }
            String content = translationService.buildDownloadContent(taskId);
            String encodedFileName = URLEncoder.encode(
                    translationService.buildTextDownloadFileName(taskId),
                    StandardCharsets.UTF_8).replace("+", "%20");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(content);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (AuthException e) {
            return authError(e);
        }
    }

    /**
     * 下载翻译后的 PDF（译文填回原位置）
     */
    @GetMapping("/download-pdf/{taskId}")
    public ResponseEntity<?> downloadPdf(@PathVariable String taskId,
                                         @RequestParam(defaultValue = "translated") String mode,
                                         HttpServletRequest request) {
        try {
            AuthUser user = authService.requireUser(request);
            TranslationSession session = translationService.getSession(taskId);
            if (!translationService.canAccess(session, user)) {
                return ResponseEntity.status(404).body(Map.of("code", 404, "message", "任务不存在"));
            }
            Path pdf = translationService.getTranslatedPdf(taskId, mode);
            String encodedFileName = URLEncoder.encode(
                    translationService.buildPdfDownloadFileName(taskId, mode),
                    StandardCharsets.UTF_8).replace("+", "%20");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(new FileSystemResource(pdf));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (AuthException e) {
            return authError(e);
        } catch (Exception e) {
            log.error("生成翻译 PDF 失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "生成翻译 PDF 失败: " + e.getMessage()));
        }
    }

    private Map<String, Object> toSummary(TranslationSession session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", session.getTaskId());
        data.put("fileName", session.getFileName());
        data.put("status", session.getStatus());
        data.put("textQualitySuspicious", session.isTextQualitySuspicious());
        data.put("textQualityWarning", session.getTextQualityWarning() != null ? session.getTextQualityWarning() : "");
        data.put("totalPages", session.getTotalPages());
        data.put("startPage", session.getStartPage());
        data.put("endPage", session.getEndPage());
        data.put("progress", session.getProgress());
        data.put("progressStageLabel", translationService.stageLabel(session.getProgressStage()));
        data.put("qps", session.getQps());
        data.put("requestedQps", session.getRequestedQps());
        data.put("resourceDowngraded", session.isResourceDowngraded());
        data.put("resourceDowngradeReason", session.getResourceDowngradeReason() != null ? session.getResourceDowngradeReason() : "");
        data.put("queuePosition", session.getQueuePosition());
        data.put("creditCost", session.getCreditCost());
        data.put("createdAt", session.getCreatedAt());
        data.put("completedAt", session.getCompletedAt());
        data.put("errorMessage", session.getErrorMessage() != null ? session.getErrorMessage() : "");
        return data;
    }

    private ResponseEntity<?> authError(AuthException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus(), "message", e.getMessage()));
    }
}
