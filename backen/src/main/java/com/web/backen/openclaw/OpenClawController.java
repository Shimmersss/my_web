package com.web.backen.openclaw;

import com.fasterxml.jackson.databind.JsonNode;
import com.web.backen.config.AdminConfig;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/openclaw")
public class OpenClawController {

    private static final int MAX_ATTACHMENTS = 5;
    private static final int MAX_ATTACHMENT_BASE64_LENGTH = 28_000_000;

    private final OpenClawService openClawService;
    private final AdminConfig adminConfig;

    public OpenClawController(OpenClawService openClawService, AdminConfig adminConfig) {
        this.openClawService = openClawService;
        this.adminConfig = adminConfig;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body) {
        String key = value(body.get("key"));
        if (!isValidKey(key)) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "管理员密钥错误"));
        }
        return ResponseEntity.ok(Map.of("code", 200, "data", Map.of("token", key)));
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                     @RequestParam(required = false) String sessionKey) {
        if (!isValidKey(key)) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "未授权"));
        }
        try {
            return ResponseEntity.ok(Map.of("code", 200, "data", openClawService.history(sessionKey)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("code", 502, "message", e.getMessage()));
        }
    }

    @PostMapping("/send")
    public ResponseEntity<?> send(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                  @RequestBody Map<String, Object> body) {
        if (!isValidKey(key)) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "未授权"));
        }

        String message = value(body.get("message"));
        if (message.length() > 10000) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "消息不能超过 10000 个字符"));
        }

        try {
            List<Map<String, Object>> attachments = attachments(body.get("attachments"));
            if (message.isBlank() && attachments.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "消息和附件不能同时为空"));
            }
            return ResponseEntity.ok(Map.of("code", 200, "data", openClawService.send(
                    value(body.get("sessionKey")),
                    message,
                    attachments
            )));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("code", 502, "message", e.getMessage()));
        }
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> sessions(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        return gatewayCall(key, openClawService::sessions);
    }

    @PostMapping("/sessions")
    public ResponseEntity<?> createSession(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return gatewayCall(key, () -> openClawService.createSession(
                body == null ? "" : value(body.get("label")),
                body == null ? "" : value(body.get("model"))
        ));
    }

    @PostMapping("/sessions/reset")
    public ResponseEntity<?> resetSession(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                          @RequestBody Map<String, Object> body) {
        return gatewayCall(key, () -> openClawService.resetSession(value(body.get("sessionKey"))));
    }

    @PatchMapping("/sessions")
    public ResponseEntity<?> patchSession(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                          @RequestBody Map<String, Object> body) {
        return gatewayCall(key, () -> openClawService.patchSession(
                value(body.get("sessionKey")),
                value(body.get("label")),
                value(body.get("model")),
                value(body.get("thinkingLevel"))
        ));
    }

    @GetMapping("/models")
    public ResponseEntity<?> models(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        return gatewayCall(key, openClawService::models);
    }

    @GetMapping("/commands")
    public ResponseEntity<?> commands(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        return gatewayCall(key, openClawService::commands);
    }

    @GetMapping("/artifacts")
    public ResponseEntity<?> artifacts(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                       @RequestParam String sessionKey) {
        return gatewayCall(key, () -> openClawService.artifacts(sessionKey));
    }

    @GetMapping("/artifacts/{artifactId}/download")
    public ResponseEntity<?> downloadArtifact(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                              @PathVariable String artifactId,
                                              @RequestParam String sessionKey) {
        if (!isValidKey(key)) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "未授权"));
        }
        try {
            JsonNode result = openClawService.downloadArtifact(sessionKey, artifactId);
            String url = result.path("url").asText("");
            if (!url.isBlank()) {
                return ResponseEntity.ok(Map.of("code", 200, "data", Map.of("url", url)));
            }

            String data = result.path("data").asText("");
            if (data.isBlank()) {
                return ResponseEntity.status(404).body(Map.of("code", 404, "message", "该产物暂不支持下载"));
            }
            JsonNode artifact = result.path("artifact");
            String filename = safeFilename(artifact.path("title").asText("openclaw-artifact"));
            String mimeType = artifact.path("mimeType").asText("application/octet-stream");
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(filename, StandardCharsets.UTF_8)
                            .build()
                            .toString())
                    .body(Base64.getDecoder().decode(data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("code", 502, "message", e.getMessage()));
        }
    }

    @GetMapping("/media/inbound/{filename:.+}")
    public ResponseEntity<?> inboundMedia(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                          @PathVariable String filename) {
        if (!isValidKey(key)) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "未授权"));
        }
        if (filename.isBlank() || filename.contains("/") || filename.contains("\\") || filename.contains("\0")) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "附件名称不合法"));
        }
        try {
            Path root = Path.of(System.getProperty("user.home"), ".openclaw", "media", "inbound").toRealPath();
            Path file = root.resolve(filename).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                return ResponseEntity.notFound().build();
            }
            String mimeType = Files.probeContentType(file);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType == null ? "application/octet-stream" : mimeType))
                    .body(Files.readAllBytes(file));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    private ResponseEntity<?> gatewayCall(String key, GatewayCall call) {
        if (!isValidKey(key)) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "未授权"));
        }
        try {
            return ResponseEntity.ok(Map.of("code", 200, "data", call.invoke()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("code", 502, "message", e.getMessage()));
        }
    }

    private boolean isValidKey(String key) {
        if (key == null || key.isBlank() || adminConfig.getKey() == null) {
            return false;
        }
        return MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8),
                adminConfig.getKey().getBytes(StandardCharsets.UTF_8)
        );
    }

    private String value(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private List<Map<String, Object>> attachments(Object rawAttachments) {
        if (rawAttachments == null) {
            return List.of();
        }
        if (!(rawAttachments instanceof List<?> items)) {
            throw new IllegalArgumentException("附件格式错误");
        }
        if (items.size() > MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("一次最多上传 " + MAX_ATTACHMENTS + " 个附件");
        }

        List<Map<String, Object>> attachments = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("附件格式错误");
            }
            String fileName = value(raw.get("fileName"));
            String mimeType = value(raw.get("mimeType"));
            String content = value(raw.get("content"));
            if (fileName.isBlank() || content.isBlank()) {
                throw new IllegalArgumentException("附件名称和内容不能为空");
            }
            if (fileName.length() > 255 || fileName.contains("/") || fileName.contains("\\")) {
                throw new IllegalArgumentException("附件名称不合法");
            }
            if (content.length() > MAX_ATTACHMENT_BASE64_LENGTH) {
                throw new IllegalArgumentException("单个附件不能超过 20 MB");
            }
            attachments.add(Map.of(
                    "fileName", fileName,
                    "mimeType", mimeType.isBlank() ? "application/octet-stream" : mimeType,
                    "content", content
            ));
        }
        return attachments;
    }

    private String safeFilename(String filename) {
        return filename.replaceAll("[\\\\/\\r\\n\"]", "_");
    }

    @FunctionalInterface
    private interface GatewayCall {
        Object invoke();
    }
}
