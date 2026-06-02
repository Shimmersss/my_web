package com.web.backen.translate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/translate")
public class TranslateController {

    private static final Logger log = LoggerFactory.getLogger(TranslateController.class);

    private final TranslationService translationService;

    public TranslateController(TranslationService translationService) {
        this.translationService = translationService;
    }

    /**
     * 上传 PDF 文件，获取页数信息（不立即翻译）
     */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
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
            byte[] pdfBytes = file.getBytes();
            TranslationSession session = translationService.createSessionPreview(fileName, pdfBytes);

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", Map.of(
                            "taskId", session.getTaskId(),
                            "fileName", session.getFileName(),
                            "totalPages", session.getTotalPages()
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
     * 开始翻译：指定页面范围，提取段落并开始翻译
     */
    @PostMapping("/start/{taskId}")
    public ResponseEntity<?> start(@PathVariable String taskId,
                                    @RequestParam(defaultValue = "1") int startPage,
                                    @RequestParam(required = false) Integer endPage) {
        try {
            TranslationSession session = translationService.startTranslation(
                    taskId, startPage, endPage != null ? endPage : Integer.MAX_VALUE);

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", Map.of(
                            "taskId", session.getTaskId(),
                            "paragraphCount", session.getTotalParagraphs()
                    )
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("开始翻译失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "处理失败: " + e.getMessage()));
        }
    }

    /**
     * SSE 流式推送翻译进度
     */
    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId) {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L); // 10 分钟超时

        emitter.onTimeout(() -> {
            log.warn("SSE 超时: taskId={}", taskId);
            emitter.complete();
        });

        emitter.onError(e -> {
            log.warn("SSE 错误: taskId={}", taskId, e);
        });

        translationService.translateAsync(taskId, emitter);
        return emitter;
    }

    /**
     * 查询翻译任务状态（用于断线重连）
     */
    @GetMapping("/status/{taskId}")
    public ResponseEntity<?> status(@PathVariable String taskId) {
        TranslationSession session = translationService.getSession(taskId);
        if (session == null) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "任务不存在"));
        }

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of(
                        "taskId", session.getTaskId(),
                        "fileName", session.getFileName(),
                        "status", session.getStatus(),
                        "totalPages", session.getTotalPages(),
                        "completedParagraphs", session.getCompletedParagraphs(),
                        "totalParagraphs", session.getTotalParagraphs(),
                        "errorMessage", session.getErrorMessage() != null ? session.getErrorMessage() : ""
                )
        ));
    }

    /**
     * 下载翻译结果（TXT）
     */
    @GetMapping("/download/{taskId}")
    public ResponseEntity<?> download(@PathVariable String taskId,
                                      @RequestParam(defaultValue = "bilingual") String mode) {
        try {
            String content = translationService.buildDownloadContent(taskId, mode);
            String encodedFileName = URLEncoder.encode("翻译结果.txt", StandardCharsets.UTF_8).replace("+", "%20");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(content);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        }
    }

    /**
     * 下载翻译后的 PDF（译文填回原位置）
     */
    @GetMapping("/download-pdf/{taskId}")
    public ResponseEntity<?> downloadPdf(@PathVariable String taskId) {
        try {
            byte[] pdf = translationService.buildTranslatedPdf(taskId);
            String encodedFileName = URLEncoder.encode("翻译版.pdf", StandardCharsets.UTF_8).replace("+", "%20");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("生成翻译 PDF 失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "生成翻译 PDF 失败: " + e.getMessage()));
        }
    }
}
