package com.web.backen.ppt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.RuntimeConfigService;
import com.web.backen.config.PptGenerationConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Generates a small, bounded set of presentation visuals before Codex authors PPTD.
 * It intentionally uses a separately configured Images API credential: a local
 * Codex CLI OAuth/session token is neither read nor forwarded to this endpoint.
 */
@Component
public class PptImageGenerationService {
    private static final long MAX_IMAGE_BYTES = 12L * 1024 * 1024;
    private final PptGenerationConfig config;
    private final RuntimeConfigService runtime;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public PptImageGenerationService(PptGenerationConfig config, RuntimeConfigService runtime, ObjectMapper objectMapper) {
        this(config, runtime, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    PptImageGenerationService(PptGenerationConfig config, RuntimeConfigService runtime, ObjectMapper objectMapper,
                              HttpClient httpClient) {
        this.config = config;
        this.runtime = runtime;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public void generate(PptGenerationSession session, Path outputDir,
                         BiConsumer<String, Map<String, Object>> events) throws IOException, InterruptedException {
        String mode = session.getImageGenerationMode();
        if ("off".equals(mode)) return;
        String apiKey = runtime.imageGenerationKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("已选择 AI 生图，但 root 后台尚未配置 GPT Image 2 的 Images API Key");
        }
        Files.createDirectories(outputDir);
        int requested = "prefer".equals(mode) ? runtime.imageGenerationMaxImages()
                : Math.min(2, runtime.imageGenerationMaxImages());
        List<String> subjects = List.of("hero scene", "process or relationship", "outcome or future scene", "detail visual");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("provider", "openai-compatible-images");
        manifest.put("model", runtime.imageGenerationModel());
        manifest.put("mode", mode);
        manifest.put("images", java.util.stream.IntStream.range(0, requested).mapToObj(i -> "ai-" + (i + 1) + ".png").toList());
        for (int index = 0; index < requested; index++) {
            events.accept("authoring", Map.of("progress", 20 + index * 4,
                    "message", "正在生成 AI 视觉素材（" + (index + 1) + "/" + requested + "）"));
            byte[] image = requestImage(prompt(session, subjects.get(index)), apiKey);
            validatePng(image);
            Files.write(outputDir.resolve("ai-" + (index + 1) + ".png"), image);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("manifest.json").toFile(), manifest);
        Files.writeString(outputDir.resolve("README.txt"), "Server-generated presentation assets. Use them only as visual material; do not display this file or invent source citations for them.\n");
    }

    private byte[] requestImage(String prompt, String apiKey) throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(Map.of(
                "model", runtime.imageGenerationModel(),
                "prompt", prompt,
                "size", "1536x1024",
                "quality", runtime.imageGenerationQuality()));
        HttpRequest request = HttpRequest.newBuilder(URI.create(runtime.imageGenerationEndpoint()))
                .timeout(Duration.ofSeconds(Math.min(180, Math.max(30, config.getCodexTimeoutSeconds() / 8))))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("GPT Image 2 生图失败：HTTP " + response.statusCode() + "："
                    + conciseError(response.body(), apiKey));
        }
        Map<String, Object> root = objectMapper.readValue(response.body(), new TypeReference<>() {});
        Object data = root.get("data");
        if (!(data instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> image)) {
            throw new IllegalStateException("GPT Image 2 未返回图片数据");
        }
        Object encoded = image.get("b64_json");
        if (!(encoded instanceof String value) || value.isBlank()) {
            throw new IllegalStateException("GPT Image 2 返回不包含 b64_json；当前中转需兼容 OpenAI Images API");
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("GPT Image 2 返回了无效的图片编码");
        }
    }

    private String prompt(PptGenerationSession session, String subject) {
        String request = session.getPrompt() == null ? "" : session.getPrompt().replaceAll("\\s+", " ").trim();
        if (request.length() > 2600) request = request.substring(0, 2600);
        return "Create one polished 16:9 presentation visual for this request: " + request + ". "
                + "Focus on the " + subject + ". Match a premium editorial/infographic visual language, leave calm negative space, "
                + "and do not render any words, labels, logos, watermark, UI, equations, citations, slide border, or tiny unreadable details.";
    }

    private void validatePng(byte[] image) {
        if (image == null || image.length < 128 || image.length > MAX_IMAGE_BYTES
                || image[0] != (byte) 0x89 || image[1] != 0x50 || image[2] != 0x4e || image[3] != 0x47) {
            throw new IllegalStateException("GPT Image 2 返回的素材不是受支持的 PNG，或体积超限");
        }
    }

    private String conciseError(String body, String apiKey) {
        String result = body == null ? "" : body.replace(apiKey, "***").replaceAll("[\\r\\n\\t]+", " ").trim();
        try {
            Map<String, Object> root = objectMapper.readValue(result, new TypeReference<>() {});
            Object error = root.get("error");
            if (error instanceof Map<?, ?> map && map.get("message") != null) result = String.valueOf(map.get("message"));
        } catch (Exception ignored) { }
        return result.isBlank() ? "上游未返回详情" : result.substring(0, Math.min(result.length(), 360));
    }
}
