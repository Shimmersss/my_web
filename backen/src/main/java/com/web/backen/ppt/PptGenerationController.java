package com.web.backen.ppt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ppt-generate")
public class PptGenerationController {

    private static final Logger log = LoggerFactory.getLogger(PptGenerationController.class);

    private final PptGenerationService pptGenerationService;

    public PptGenerationController(PptGenerationService pptGenerationService) {
        this.pptGenerationService = pptGenerationService;
    }

    @PostMapping("/tasks")
    public ResponseEntity<?> createTask(@RequestParam("prompt") String prompt,
                                        @RequestParam(value = "templateKey", required = false) String templateKey,
                                        @RequestParam(value = "templateMode", required = false) String templateMode,
                                        @RequestParam(value = "extractionPercent", required = false, defaultValue = "50") int extractionPercent,
                                        @RequestParam(value = "templateFile", required = false) MultipartFile templateFile,
                                        @RequestParam(value = "paperFile", required = false) MultipartFile paperFile) {
        try {
            PptGenerationSession session = pptGenerationService.createTask(prompt, templateKey, templateMode, extractionPercent, templateFile, paperFile);
            Map<String, Object> data = toSummary(session);
            data.put("accessToken", session.getAccessToken());
            return ResponseEntity.ok(Map.of("code", 200, "data", data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(429).body(Map.of("code", 429, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("创建 PPT 生成任务失败", e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "PPT 生成任务创建失败: " + e.getMessage()));
        }
    }

    @GetMapping("/templates")
    public ResponseEntity<?> templates() {
        return ResponseEntity.ok(Map.of("code", 200, "data", pptGenerationService.templates()));
    }

    @GetMapping("/deck/{taskId}")
    public ResponseEntity<?> deck(@PathVariable String taskId,
                                  @RequestHeader(value = "X-Ppt-Task-Token", required = false) String accessToken) {
        try {
            return ResponseEntity.ok(Map.of("code", 200, "data", pptGenerationService.getDeck(taskId, accessToken)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        }
    }

    @PutMapping("/deck/{taskId}")
    public ResponseEntity<?> updateDeck(@PathVariable String taskId,
                                        @RequestHeader(value = "X-Ppt-Task-Token", required = false) String accessToken,
                                        @RequestBody JsonNode deck) {
        try {
            return ResponseEntity.ok(Map.of("code", 200, "data", pptGenerationService.updateDeck(taskId, accessToken, deck)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("code", 409, "message", e.getMessage()));
        }
    }

    @PostMapping("/revise/{taskId}")
    public ResponseEntity<?> revise(@PathVariable String taskId,
                                    @RequestHeader(value = "X-Ppt-Task-Token", required = false) String accessToken,
                                    @RequestBody ReviseRequest request) {
        try {
            JsonNode deck = pptGenerationService.reviseDeck(taskId, accessToken, request.instruction(), request.deck());
            return ResponseEntity.ok(Map.of("code", 200, "data", deck));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(429).body(Map.of("code", 429, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("修改 PPT 大纲失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "修改 PPT 大纲失败: " + e.getMessage()));
        }
    }

    @PostMapping("/render/{taskId}")
    public ResponseEntity<?> render(@PathVariable String taskId,
                                    @RequestHeader(value = "X-Ppt-Task-Token", required = false) String accessToken,
                                    @RequestBody JsonNode request) {
        try {
            JsonNode deck = request != null && request.has("deck") ? request.get("deck") : request;
            String templateMode = request != null && request.hasNonNull("templateMode") ? request.path("templateMode").asText() : null;
            Integer extractionPercent = request != null && request.hasNonNull("extractionPercent") ? request.path("extractionPercent").asInt() : null;
            PptGenerationSession session = pptGenerationService.renderTask(taskId, accessToken, deck, templateMode, extractionPercent);
            return ResponseEntity.ok(Map.of("code", 200, "data", toSummary(session)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(429).body(Map.of("code", 429, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("渲染 PPT 失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "渲染 PPT 失败: " + e.getMessage()));
        }
    }

    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId,
                             @RequestParam(value = "accessToken", required = false) String accessToken) {
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        emitter.onTimeout(() -> {
            log.warn("PPT SSE 超时: taskId={}", taskId);
            emitter.complete();
        });
        emitter.onError(e -> log.warn("PPT SSE 错误: taskId={}", taskId, e));
        pptGenerationService.subscribe(taskId, accessToken, emitter);
        return emitter;
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<?> status(@PathVariable String taskId,
                                    @RequestHeader(value = "X-Ppt-Task-Token", required = false) String accessToken) {
        try {
            PptGenerationSession session = pptGenerationService.getAuthorizedSession(taskId, accessToken);
            return ResponseEntity.ok(Map.of("code", 200, "data", toSummary(session)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "任务不存在"));
        }
    }

    @GetMapping("/recent")
    public ResponseEntity<?> recent(@RequestHeader(value = "X-Ppt-Task-Tokens", required = false) String accessTokens) {
        List<Map<String, Object>> data = pptGenerationService.getRecentSessions(accessTokens).stream()
                .map(this::toSummary)
                .toList();
        return ResponseEntity.ok(Map.of("code", 200, "data", data));
    }

    @GetMapping("/download/{taskId}")
    public ResponseEntity<?> download(@PathVariable String taskId,
                                      @RequestParam(value = "accessToken", required = false) String accessToken) {
        try {
            PptGenerationSession session = pptGenerationService.getAuthorizedSession(taskId, accessToken);
            Path output = pptGenerationService.getOutput(taskId, accessToken);
            String fileName = session != null && session.getOutputFileName() != null
                    ? session.getOutputFileName()
                    : "AI生成PPT-" + taskId + ".pptx";
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                    .body(new FileSystemResource(output));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("下载 PPT 失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "下载 PPT 失败: " + e.getMessage()));
        }
    }

    private Map<String, Object> toSummary(PptGenerationSession session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", session.getTaskId());
        data.put("prompt", session.getPrompt());
        data.put("templateKey", session.getTemplateKey());
        data.put("templateFileName", session.getTemplateFileName() == null ? "" : session.getTemplateFileName());
        data.put("templateMode", session.getTemplateMode());
        data.put("extractionPercent", session.getExtractionPercent());
        data.put("paperFileName", session.getPaperFileName() == null ? "" : session.getPaperFileName());
        data.put("outputFileName", session.getOutputFileName() == null ? "" : session.getOutputFileName());
        data.put("status", session.getStatus());
        data.put("progress", session.getProgress());
        data.put("progressStage", session.getProgressStage());
        data.put("progressStageLabel", pptGenerationService.stageLabel(session.getProgressStage()));
        data.put("errorMessage", session.getErrorMessage() == null ? "" : session.getErrorMessage());
        data.put("queuePosition", session.getQueuePosition());
        data.put("createdAt", session.getCreatedAt());
        data.put("updatedAt", session.getUpdatedAt());
        data.put("completedAt", session.getCompletedAt());
        return data;
    }

    public record ReviseRequest(String instruction, JsonNode deck) {}
}
