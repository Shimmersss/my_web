package com.web.backen.ppt;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final AuthService authService;
    private final QuotaService quotaService;

    public PptGenerationController(PptGenerationService pptGenerationService, AuthService authService, QuotaService quotaService) {
        this.pptGenerationService = pptGenerationService;
        this.authService = authService;
        this.quotaService = quotaService;
    }

    @PostMapping("/tasks")
    public ResponseEntity<?> createTask(@RequestParam("prompt") String prompt,
                                        @RequestParam(value = "templateKey", required = false) String templateKey,
                                        @RequestParam(value = "extractionPercent", required = false, defaultValue = "50") int extractionPercent,
                                        @RequestParam(value = "templateFile", required = false) MultipartFile templateFile,
                                        @RequestParam(value = "paperFile", required = false) MultipartFile paperFile,
                                        HttpServletRequest request) {
        AuthUser user;
        try {
            authService.requireCsrf(request);
            user = authService.requireUser(request);
        } catch (AuthException e) {
            return authError(e);
        }
        try {
            PptGenerationSession session = pptGenerationService.createTask(prompt, templateKey, extractionPercent, templateFile, paperFile, user);
            Map<String, Object> data = toSummary(session);
            data.put("accessToken", session.getAccessToken());
            data.put("credits", quotaService.balance(user.id()));
            return ResponseEntity.ok(Map.of("code", 200, "data", data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(429).body(Map.of("code", 429, "message", e.getMessage()));
        } catch (AuthException e) {
            return authError(e);
        } catch (Exception e) {
            log.error("创建 PPT 生成任务失败", e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "PPT 生成任务创建失败: " + e.getMessage()));
        }
    }

    @GetMapping("/templates")
    public ResponseEntity<?> templates() {
        return ResponseEntity.ok(Map.of("code", 200, "data", pptGenerationService.templates()));
    }

    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId,
                             @RequestParam(value = "accessToken", required = false) String accessToken,
                             HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        AuthUser user = authService.currentUser(request).orElse(null);
        PptGenerationSession session = pptGenerationService.getSession(taskId);
        if (!pptGenerationService.canAccess(session, user)) {
            try {
                session = pptGenerationService.getAuthorizedSession(taskId, accessToken);
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("task-error").data(Map.of("message", "任务不存在")));
                } catch (Exception ignored) {}
                emitter.complete();
                return emitter;
            }
        }
        emitter.onTimeout(() -> {
            log.warn("PPT SSE 超时: taskId={}", taskId);
            emitter.complete();
        });
        emitter.onError(e -> log.warn("PPT SSE 错误: taskId={}", taskId, e));
        pptGenerationService.subscribe(session, emitter);
        return emitter;
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<?> status(@PathVariable String taskId,
                                    @RequestHeader(value = "X-Ppt-Task-Token", required = false) String accessToken,
                                    HttpServletRequest request) {
        try {
            AuthUser user = authService.currentUser(request).orElse(null);
            PptGenerationSession session = pptGenerationService.getSession(taskId);
            if (!pptGenerationService.canAccess(session, user)) {
                session = pptGenerationService.getAuthorizedSession(taskId, accessToken);
            }
            return ResponseEntity.ok(Map.of("code", 200, "data", toSummary(session)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "任务不存在"));
        }
    }

    @GetMapping("/recent")
    public ResponseEntity<?> recent(@RequestHeader(value = "X-Ppt-Task-Tokens", required = false) String accessTokens,
                                    HttpServletRequest request) {
        AuthUser user = authService.currentUser(request).orElse(null);
        List<Map<String, Object>> data = pptGenerationService.getRecentSessions(user, accessTokens).stream()
                .map(this::toSummary)
                .toList();
        return ResponseEntity.ok(Map.of("code", 200, "data", data));
    }

    @GetMapping("/download/{taskId}")
    public ResponseEntity<?> download(@PathVariable String taskId,
                                      @RequestParam(value = "accessToken", required = false) String accessToken,
                                      HttpServletRequest request) {
        try {
            AuthUser user = authService.currentUser(request).orElse(null);
            PptGenerationSession session = pptGenerationService.getSession(taskId);
            Path output;
            if (pptGenerationService.canAccess(session, user)) {
                output = pptGenerationService.getOutput(taskId);
            } else {
                session = pptGenerationService.getAuthorizedSession(taskId, accessToken);
                output = pptGenerationService.getOutput(taskId, accessToken);
            }
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
        data.put("extractionPercent", session.getExtractionPercent());
        data.put("paperFileName", session.getPaperFileName() == null ? "" : session.getPaperFileName());
        data.put("outputFileName", session.getOutputFileName() == null ? "" : session.getOutputFileName());
        data.put("status", session.getStatus());
        data.put("progress", session.getProgress());
        data.put("progressStage", session.getProgressStage());
        data.put("progressStageLabel", pptGenerationService.stageLabel(session.getProgressStage()));
        data.put("errorMessage", session.getErrorMessage() == null ? "" : session.getErrorMessage());
        data.put("queuePosition", session.getQueuePosition());
        data.put("creditCost", session.getCreditCost());
        data.put("createdAt", session.getCreatedAt());
        data.put("updatedAt", session.getUpdatedAt());
        data.put("completedAt", session.getCompletedAt());
        return data;
    }

    private ResponseEntity<?> authError(AuthException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus(), "message", e.getMessage()));
    }
}
