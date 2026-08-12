package com.web.backen.imagegen;

import com.web.backen.auth.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/image-generate")
public class ImageGenerationController {
    private static final Logger log = LoggerFactory.getLogger(ImageGenerationController.class);
    private final ImageGenerationService service;
    private final AuthService auth;
    private final QuotaService quota;
    private final RuntimeConfigService runtime;

    public ImageGenerationController(ImageGenerationService service, AuthService auth, QuotaService quota, RuntimeConfigService runtime) {
        this.service = service; this.auth = auth; this.quota = quota; this.runtime = runtime;
    }

    @PostMapping("/tasks")
    public ResponseEntity<?> create(@RequestParam String prompt,
                                    @RequestParam(defaultValue = "GENERATE") String mode,
                                    @RequestParam(defaultValue = "1024x1024") String size,
                                    @RequestParam(defaultValue = "medium") String quality,
                                    @RequestParam(required = false) String parentTaskId,
                                    @RequestParam(required = false) MultipartFile referenceFile,
                                    HttpServletRequest request) {
        try {
            AuthUser user = requireAccess(request); auth.requireCsrf(request);
            ImageGenerationSession session = service.create(prompt, mode, size, quality, parentTaskId, referenceFile, user);
            return ok(Map.of("task", service.summary(session), "credits", quota.balance(user.id())));
        } catch (AuthException e) { return error(e.getStatus(), e.getMessage()); }
        catch (IllegalArgumentException e) { return error(400, e.getMessage()); }
        catch (IllegalStateException e) { return error(429, e.getMessage()); }
        catch (Exception e) { log.error("创建生图任务失败", e); return error(500, "创建生图任务失败"); }
    }

    @GetMapping("/stream/{taskId}")
    public SseEmitter stream(@PathVariable String taskId, HttpServletRequest request) {
        return service.subscribe(taskId, requireAccess(request));
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<?> status(@PathVariable String taskId, HttpServletRequest request) {
        try { return ok(service.summary(service.requireReadable(taskId, requireAccess(request)))); }
        catch (AuthException e) { return error(e.getStatus(), e.getMessage()); }
        catch (IllegalArgumentException e) { return error(404, e.getMessage()); }
    }

    @GetMapping("/recent")
    public ResponseEntity<?> recent(HttpServletRequest request) {
        try {
            AuthUser user = requireAccess(request);
            List<Map<String, Object>> tasks = service.recent(user).stream().map(service::summary).toList();
            return ok(Map.of("tasks", tasks, "credits", quota.balance(user.id()), "costs", Map.of(
                    "low", quota.imageCredit("low"), "medium", quota.imageCredit("medium"), "high", quota.imageCredit("high"))));
        } catch (AuthException e) { return error(e.getStatus(), e.getMessage()); }
    }

    @GetMapping("/result/{taskId}")
    public ResponseEntity<?> result(@PathVariable String taskId, HttpServletRequest request) {
        try {
            ImageGenerationSession session = service.requireReadable(taskId, requireAccess(request));
            if (!"completed".equals(session.getStatus()) || !Files.isRegularFile(session.getResultPath())) throw new IllegalArgumentException("图片尚未生成");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=codex-image-" + taskId + ".png")
                    .body(new FileSystemResource(session.getResultPath()));
        } catch (AuthException e) { return error(e.getStatus(), e.getMessage()); }
        catch (IllegalArgumentException e) { return error(404, e.getMessage()); }
    }

    @GetMapping("/preview/{taskId}")
    public ResponseEntity<?> preview(@PathVariable String taskId, HttpServletRequest request) {
        try {
            ImageGenerationSession session = service.requireReadable(taskId, requireAccess(request));
            if (!Files.isRegularFile(session.getPreviewPath())) throw new IllegalArgumentException("预览尚未生成");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
                    .body(new FileSystemResource(session.getPreviewPath()));
        } catch (AuthException e) { return error(e.getStatus(), e.getMessage()); }
        catch (IllegalArgumentException e) { return error(404, e.getMessage()); }
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<?> delete(@PathVariable String taskId, HttpServletRequest request) {
        try { AuthUser user = requireAccess(request); auth.requireCsrf(request); service.delete(taskId, user); return ok(Map.of("deleted", true)); }
        catch (AuthException e) { return error(e.getStatus(), e.getMessage()); }
        catch (IllegalArgumentException e) { return error(404, e.getMessage()); }
        catch (IllegalStateException e) { return error(409, e.getMessage()); }
    }

    private AuthUser requireAccess(HttpServletRequest request) {
        AuthUser user = auth.requireUser(request);
        if ("ROOT".equalsIgnoreCase(runtime.visibilityLevel("ImageGenerate")) && !user.isRoot()) throw new AuthException(403, "无权访问该节目");
        return user;
    }
    private ResponseEntity<?> ok(Object data) { return ResponseEntity.ok(Map.of("code", 200, "data", data)); }
    private ResponseEntity<?> error(int status, String message) { return ResponseEntity.status(status).body(Map.of("code", status, "message", message)); }
}
