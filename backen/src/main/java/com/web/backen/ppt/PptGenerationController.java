package com.web.backen.ppt;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.settings.RuntimeConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
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
import java.util.ArrayList;

@RestController
@RequestMapping("/api/ppt-generate")
public class PptGenerationController {

    private static final Logger log = LoggerFactory.getLogger(PptGenerationController.class);

    private final PptGenerationService pptGenerationService;
    private final AuthService authService;
    private final QuotaService quotaService;
    private final RuntimeConfigService runtimeConfig;

    public PptGenerationController(PptGenerationService pptGenerationService, AuthService authService, QuotaService quotaService,
                                   RuntimeConfigService runtimeConfig) {
        this.pptGenerationService = pptGenerationService;
        this.authService = authService;
        this.quotaService = quotaService;
        this.runtimeConfig = runtimeConfig;
    }

    @PostMapping("/tasks")
    public ResponseEntity<?> createTask(@RequestParam(value = "prompt", required = false, defaultValue = "") String prompt,
                                        @RequestParam(value = "templateKey", required = false) String templateKey,
                                        @RequestParam(value = "outputFormat", required = false, defaultValue = "pptx") String outputFormat,
                                        @RequestParam(value = "researchMode", required = false, defaultValue = "auto") String researchMode,
                                        @RequestParam(value = "visualMode", required = false, defaultValue = "best_effort") String visualMode,
                                        @RequestParam(value = "motionMode", required = false, defaultValue = "auto") String motionMode,
                                        @RequestParam(value = "imageGenerationMode", required = false, defaultValue = "off") String imageGenerationMode,
                                        @RequestParam(value = "pageCount", required = false) Integer pageCount,
                                        @RequestParam(value = "imageGenerationCount", required = false) Integer imageGenerationCount,
                                        @RequestParam(value = "fontFamily", required = false, defaultValue = "Microsoft YaHei") String fontFamily,
                                        @RequestParam(value = "templateFile", required = false) MultipartFile templateFile,
                                        @RequestParam(value = "sourceFile", required = false) MultipartFile sourceFile,
                                        @RequestParam(value = "paperFile", required = false) MultipartFile legacyPaperFile,
                                        @RequestHeader(value = "X-Ppt-Idempotency-Key", required = false) String clientRequestId,
                                        HttpServletRequest request) {
        AuthUser user;
        try {
            requirePptFeatureAccess(request);
            authService.requireCsrf(request);
            user = requirePptTaskUser(request);
        } catch (AuthException e) {
            return authError(e);
        }
        try {
            MultipartFile materialFile = sourceFile != null && !sourceFile.isEmpty() ? sourceFile : legacyPaperFile;
            PptGenerationSession session = pptGenerationService.createTask(prompt, templateKey, 100,
                    templateFile, materialFile, user, clientRequestId, outputFormat, researchMode, visualMode,
                    fontFamily, imageGenerationMode, motionMode, pageCount, imageGenerationCount);
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
    public ResponseEntity<?> templates(HttpServletRequest request) {
        try {
            requirePptFeatureAccess(request);
            return ResponseEntity.ok(Map.of("code", 200, "data", pptGenerationService.templates()));
        } catch (AuthException e) {
            return authError(e);
        }
    }

    @PostMapping("/tasks/{taskId}/revise")
    public ResponseEntity<?> reviseTask(@PathVariable String taskId,
                                        @RequestHeader(value = "X-Ppt-Task-Token", required = false) String accessToken,
                                        @RequestHeader(value = "X-Ppt-Idempotency-Key", required = false) String clientRequestId,
                                        @RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest request) {
        AuthUser user;
        try {
            requirePptFeatureAccess(request);
            authService.requireCsrf(request);
            user = requirePptTaskUser(request);
        } catch (AuthException e) {
            return authError(e);
        }
        try {
            PptGenerationSession original = pptGenerationService.getSession(taskId);
            if (!pptGenerationService.canAccess(original, user)) {
                original = pptGenerationService.getAuthorizedSession(taskId, accessToken);
            }
            String prompt = body == null ? "" : String.valueOf(body.getOrDefault("prompt", ""));
            PptGenerationSession session = pptGenerationService.createRevisionTask(
                    original, prompt, List.of(), user, clientRequestId);
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
            log.error("创建 PPT 二次修改任务失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "PPT 二次修改任务创建失败: " + e.getMessage()));
        }
    }

    @GetMapping("/preview/{taskId}")
    public ResponseEntity<?> preview(@PathVariable String taskId,
                                     @RequestHeader(value = "X-Ppt-Task-Token", required = false) String accessToken,
                                     HttpServletRequest request) {
        try {
            requirePptFeatureAccess(request);
            AuthUser user = authService.currentUser(request).orElse(null);
            PptGenerationSession session = pptGenerationService.getSession(taskId);
            if (!pptGenerationService.canAccess(session, user)) {
                session = pptGenerationService.getAuthorizedSession(taskId, accessToken);
            }
            return ResponseEntity.ok(Map.of("code", 200, "data", pptGenerationService.preview(session)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", e.getMessage()));
        } catch (AuthException e) {
            return authError(e);
        } catch (Exception e) {
            log.error("读取 PPT 网页预览失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "PPT 预览暂时不可用"));
        }
    }

    @GetMapping("/preview/{taskId}/images/{fileName:.+}")
    public ResponseEntity<?> previewImage(@PathVariable String taskId,
                                          @PathVariable String fileName,
                                          @RequestHeader(value = "X-Ppt-Task-Token", required = false) String accessToken,
                                          HttpServletRequest request) {
        try {
            requirePptFeatureAccess(request);
            AuthUser user = authService.currentUser(request).orElse(null);
            PptGenerationSession session = pptGenerationService.getSession(taskId);
            if (!pptGenerationService.canAccess(session, user)) {
                session = pptGenerationService.getAuthorizedSession(taskId, accessToken);
            }
            Path image = pptGenerationService.previewImage(session, fileName);
            MediaType type = MediaTypeFactory.getMediaType(image.getFileName().toString())
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                    .contentType(type).body(new FileSystemResource(image));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "预览素材不存在"));
        } catch (AuthException e) {
            return authError(e);
        } catch (Exception e) {
            log.error("读取 PPT 预览素材失败: taskId={}, file={}", taskId, fileName, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "预览素材暂时不可用"));
        }
    }

    @GetMapping("/preview-html/{taskId}")
    public ResponseEntity<?> previewHtml(@PathVariable String taskId,
                                         @RequestHeader(value = "X-Ppt-Task-Token", required = false) String headerToken,
                                         HttpServletRequest request) {
        try {
            requirePptFeatureAccess(request);
            AuthUser user = authService.currentUser(request).orElse(null);
            PptGenerationSession session = pptGenerationService.getSession(taskId);
            boolean sessionAuthorized = pptGenerationService.canAccess(session, user);
            if (!sessionAuthorized) {
                session = pptGenerationService.getAuthorizedSession(taskId, headerToken);
            }
            if (!"html".equalsIgnoreCase(session.getOutputFormat())) {
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "该任务不是 HTML 输出"));
            }
            Path output = sessionAuthorized ? pptGenerationService.getOutput(taskId) : pptGenerationService.getOutput(taskId, headerToken);
            String fileName = session.getOutputFileName() == null ? "AI生成演示.html" : session.getOutputFileName();
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedFileName)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("Content-Security-Policy", "default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src data:")
                    .header("Cross-Origin-Resource-Policy", "same-origin")
                    .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                    .body(new FileSystemResource(output));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "预览不存在"));
        } catch (AuthException e) {
            return authError(e);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("读取 reveal.js HTML 预览失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "HTML 预览暂时不可用"));
        }
    }

    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId,
                             HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        try {
            requirePptFeatureAccess(request);
        } catch (AuthException e) {
            try { emitter.send(SseEmitter.event().name("task-error").data(Map.of("message", e.getMessage()))); }
            catch (Exception ignored) { }
            emitter.complete();
            return emitter;
        }
        AuthUser user = authService.currentUser(request).orElse(null);
        PptGenerationSession session = pptGenerationService.getSession(taskId);
        if (!pptGenerationService.canAccess(session, user)) {
            try { emitter.send(SseEmitter.event().name("task-error").data(Map.of("message", "任务不存在"))); }
            catch (Exception ignored) {}
            emitter.complete();
            return emitter;
        }
        emitter.onTimeout(() -> {
            log.warn("PPT SSE 超时: taskId={}", taskId);
            emitter.complete();
        });
        emitter.onError(e -> log.warn("PPT SSE 错误: taskId={}", taskId, e));
        pptGenerationService.subscribe(session, emitter);
        return emitter;
    }

    @GetMapping("/tasks/{taskId}/project")
    public ResponseEntity<?> project(@PathVariable String taskId,
                                     @RequestHeader(value = "X-Ppt-Task-Token", required = false) String accessToken,
                                     HttpServletRequest request) {
        try {
            PptGenerationSession session = authorized(taskId, accessToken, request);
            return ResponseEntity.ok(Map.of("code", 200, "data", pptGenerationService.pptdProject(session)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", e.getMessage()));
        } catch (AuthException e) {
            return authError(e);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        }
    }

    @GetMapping("/tasks/{taskId}/project/files/{*filePath}")
    public ResponseEntity<?> projectFile(@PathVariable String taskId, @PathVariable String filePath,
                                         @RequestHeader(value = "X-Ppt-Task-Token", required = false) String accessToken,
                                         HttpServletRequest request) {
        try {
            PptGenerationSession session = authorized(taskId, accessToken, request);
            String clean = filePath == null ? "" : filePath.replaceFirst("^/+", "");
            Path file = pptGenerationService.pptdProjectFile(session, clean);
            MediaType type = MediaTypeFactory.getMediaType(file.getFileName().toString()).orElse(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                    .contentType(type).body(new FileSystemResource(file));
        } catch (Exception e) {
            if (e instanceof AuthException authException) return authError(authException);
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "PPTD 媒体不存在"));
        }
    }

    @PostMapping("/tasks/{taskId}/versions")
    public ResponseEntity<?> createVersion(@PathVariable String taskId,
                                           @RequestHeader(value = "X-Ppt-Task-Token", required = false) String accessToken,
                                           @RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        try {
            requirePptFeatureAccess(request);
            authService.requireCsrf(request);
            AuthUser user = requirePptTaskUser(request);
            PptGenerationSession parent = authorized(taskId, accessToken, request);
            int baseVersion = body.get("baseVersion") instanceof Number value ? value.intValue() : 0;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> changes = body.get("changes") instanceof List<?> list
                    ? (List<Map<String, Object>>) (List<?>) list : List.of();
            PptGenerationSession session = pptGenerationService.createManualVersion(parent, baseVersion, changes, user);
            Map<String, Object> data = toSummary(session);
            data.put("accessToken", session.getAccessToken());
            return ResponseEntity.ok(Map.of("code", 200, "data", data));
        } catch (AuthException e) {
            return authError(e);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("code", 409, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        }
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<?> status(@PathVariable String taskId,
                                    @RequestHeader(value = "X-Ppt-Task-Token", required = false) String accessToken,
                                    HttpServletRequest request) {
        try {
            requirePptFeatureAccess(request);
            AuthUser user = authService.currentUser(request).orElse(null);
            PptGenerationSession session = pptGenerationService.getSession(taskId);
            if (!pptGenerationService.canAccess(session, user)) {
                session = pptGenerationService.getAuthorizedSession(taskId, accessToken);
            }
            return ResponseEntity.ok(Map.of("code", 200, "data", toSummary(session)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "任务不存在"));
        } catch (AuthException e) {
            return authError(e);
        }
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String taskId, HttpServletRequest request) {
        try {
            requirePptFeatureAccess(request); authService.requireCsrf(request);
            AuthUser user = requirePptTaskUser(request); pptGenerationService.cancel(taskId, user);
            return ResponseEntity.ok(Map.of("code", 200, "data", Map.of("cancelled", true, "credits", quotaService.balance(user.id()))));
        } catch (AuthException e) { return authError(e); }
        catch (IllegalArgumentException e) { return ResponseEntity.status(404).body(Map.of("code", 404, "message", e.getMessage())); }
        catch (IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("code", 409, "message", e.getMessage())); }
    }

    @GetMapping("/recent")
    public ResponseEntity<?> recent(@RequestHeader(value = "X-Ppt-Task-Tokens", required = false) String accessTokens,
                                    HttpServletRequest request) {
        try {
            requirePptFeatureAccess(request);
        } catch (AuthException e) {
            return authError(e);
        }
        AuthUser user = authService.currentUser(request).orElse(null);
        List<Map<String, Object>> data = pptGenerationService.getRecentSessions(user, accessTokens).stream()
                .map(this::toSummary)
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", 200); payload.put("data", data);
        payload.putAll(pptGenerationService.recentMetadata(user));
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/download/{taskId}")
    public ResponseEntity<?> download(@PathVariable String taskId,
                                      @RequestHeader(value = "X-Ppt-Task-Token", required = false) String accessToken,
                                      @RequestParam(value = "artifact", required = false, defaultValue = "pptx") String artifact,
                                      HttpServletRequest request) {
        try {
            requirePptFeatureAccess(request);
            AuthUser user = authService.currentUser(request).orElse(null);
            PptGenerationSession session = pptGenerationService.getSession(taskId);
            Path output;
            if (pptGenerationService.canAccess(session, user)) {
                output = pptGenerationService.artifact(session, artifact);
            } else {
                session = pptGenerationService.getAuthorizedSession(taskId, accessToken);
                output = pptGenerationService.artifact(session, artifact);
            }
            boolean pptd = "pptd".equalsIgnoreCase(artifact);
            boolean html = !pptd && session != null && "html".equalsIgnoreCase(session.getOutputFormat());
            String fileName = session != null && session.getOutputFileName() != null
                    ? session.getOutputFileName()
                    : "AI生成PPT-" + taskId + (html ? ".html" : ".pptx");
            if (pptd) fileName = "PPTD项目-" + taskId + ".zip";
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            MediaType contentType = pptd ? MediaType.parseMediaType("application/zip") : html
                    ? MediaType.parseMediaType("text/html;charset=UTF-8")
                    : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                    .contentType(contentType)
                    .body(new FileSystemResource(output));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", e.getMessage()));
        } catch (AuthException e) {
            return authError(e);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("下载 PPT 失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "下载 PPT 失败: " + e.getMessage()));
        }
    }

    private PptGenerationSession authorized(String taskId, String accessToken, HttpServletRequest request) {
        requirePptFeatureAccess(request);
        AuthUser user = authService.currentUser(request).orElse(null);
        PptGenerationSession session = pptGenerationService.getSession(taskId);
        return pptGenerationService.canAccess(session, user)
                ? session : pptGenerationService.getAuthorizedSession(taskId, accessToken);
    }

    private Map<String, Object> toSummary(PptGenerationSession session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", session.getTaskId());
        data.put("userId", session.getUserId());
        data.put("prompt", session.getPrompt());
        data.put("templateKey", session.getTemplateKey());
        data.put("outputFormat", session.getOutputFormat());
        data.put("researchMode", session.getResearchMode());
        data.put("visualMode", session.getVisualMode());
        data.put("motionMode", session.getMotionMode());
        data.put("imageGenerationMode", session.getImageGenerationMode());
        data.put("requestedPageCount", session.getRequestedPageCount());
        data.put("requestedImageGenerationCount", session.getRequestedImageGenerationCount());
        data.put("fontFamily", session.getFontFamily());
        data.put("templateFileName", session.getTemplateFileName() == null ? "" : session.getTemplateFileName());
        data.put("sourceFileName", session.getPaperFileName() == null ? "" : session.getPaperFileName());
        data.put("paperFileName", session.getPaperFileName() == null ? "" : session.getPaperFileName());
        data.put("outputFileName", session.getOutputFileName() == null ? "" : session.getOutputFileName());
        data.put("status", session.getStatus());
        data.put("progress", session.getProgress());
        data.put("progressStage", session.getProgressStage());
        data.put("progressStageLabel", pptGenerationService.stageLabel(session.getProgressStage()));
        data.put("errorMessage", session.getErrorMessage() == null ? "" : session.getErrorMessage());
        data.put("queuePosition", session.getQueuePosition());
        data.put("creditCost", session.getCreditCost());
        data.put("imageGenerationCount", session.getImageGenerationCount());
        data.put("imageGenerationCreditCost", session.getImageGenerationCreditCost());
        data.put("refundPending", session.isRefundPending());
        data.put("refundError", session.getRefundError() == null ? "" : session.getRefundError());
        data.put("revisionOfTaskId", session.getRevisionOfTaskId() == null ? "" : session.getRevisionOfTaskId());
        data.put("sourceCount", session.getSourceCount());
        data.put("agentIteration", session.getAgentIteration());
        data.put("qaValid", session.isQaValid());
        data.put("engine", session.getEngine());
        data.put("version", session.getVersion());
        data.put("parentTaskId", session.getParentTaskId() == null ? "" : session.getParentTaskId());
        data.put("editorAvailable", session.isEditorAvailable());
        data.put("pptdAvailable", session.isPptdAvailable());
        data.put("previewAvailable", "completed".equals(session.getStatus()));
        data.put("createdAt", session.getCreatedAt());
        data.put("updatedAt", session.getUpdatedAt());
        data.put("completedAt", session.getCompletedAt());
        return data;
    }

    private ResponseEntity<?> authError(AuthException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus(), "message", e.getMessage()));
    }

    /**
     * PPT maps to the Contact programme in runtime visibility settings.  The
     * programme policy governs every PPT endpoint; task mutation additionally
     * requires an account because a task must retain an owner and quota ledger.
     */
    private void requirePptFeatureAccess(HttpServletRequest request) {
        String level = runtimeConfig.visibilityLevel("Contact");
        if ("ROOT".equalsIgnoreCase(level)) authService.requireRoot(request);
        else if ("USER".equalsIgnoreCase(level)) authService.requireUser(request);
    }

    private AuthUser requirePptTaskUser(HttpServletRequest request) {
        return authService.requireUser(request);
    }
}
