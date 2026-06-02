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
import java.util.LinkedHashMap;
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
     * 开始翻译：指定页面范围并交给 BabelDOC
     */
    @PostMapping("/start/{taskId}")
    public ResponseEntity<?> start(@PathVariable String taskId,
                                    @RequestParam(defaultValue = "1") int startPage,
                                    @RequestParam(required = false) Integer endPage,
                                    @RequestParam(defaultValue = "auto") String fontFamily,
                                    @RequestParam(defaultValue = "8") int qps) {
        try {
            TranslationSession session = translationService.startTranslation(
                    taskId, startPage, endPage != null ? endPage : Integer.MAX_VALUE, fontFamily, qps);

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", Map.of(
                            "taskId", session.getTaskId(),
                            "pageCount", session.getEndPage() - session.getStartPage() + 1
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
        SseEmitter emitter = new SseEmitter(35 * 60 * 1000L); // BabelDOC 首次运行需要下载资源

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

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", session.getTaskId());
        data.put("fileName", session.getFileName());
        data.put("status", session.getStatus());
        data.put("totalPages", session.getTotalPages());
        data.put("startPage", session.getStartPage());
        data.put("endPage", session.getEndPage());
        data.put("fontFamily", session.getFontFamily());
        data.put("qps", session.getQps());
        data.put("progress", session.getProgress());
        data.put("progressStage", session.getProgressStage());
        data.put("progressStageLabel", translationService.stageLabel(session.getProgressStage()));
        data.put("errorMessage", session.getErrorMessage() != null ? session.getErrorMessage() : "");
        return ResponseEntity.ok(Map.of("code", 200, "data", data));
    }

    /**
     * 下载翻译结果（TXT）
     */
    @GetMapping("/download/{taskId}")
    public ResponseEntity<?> download(@PathVariable String taskId) {
        try {
            String content = translationService.buildDownloadContent(taskId);
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
    public ResponseEntity<?> downloadPdf(@PathVariable String taskId,
                                         @RequestParam(defaultValue = "translated") String mode) {
        try {
            byte[] pdf = translationService.buildTranslatedPdf(taskId, mode);
            String fileName = "bilingual".equals(mode) ? "双语对照版.pdf" : "翻译版.pdf";
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

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
