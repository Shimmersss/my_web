package com.web.backen.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.RuntimeConfigService;
import com.web.backen.imagegen.OpenAiImageClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final Logger log = LoggerFactory.getLogger(PptImageGenerationService.class);
    private static final long MAX_IMAGE_BYTES = 12L * 1024 * 1024;
    private final RuntimeConfigService runtime;
    private final ObjectMapper objectMapper;
    private final OpenAiImageClient imageClient;

    @Autowired
    public PptImageGenerationService(RuntimeConfigService runtime, ObjectMapper objectMapper, OpenAiImageClient imageClient) {
        this.runtime = runtime;
        this.objectMapper = objectMapper;
        this.imageClient = imageClient;
    }

    public void generate(PptGenerationSession session, Path outputDir,
                         BiConsumer<String, Map<String, Object>> events) throws IOException, InterruptedException {
        String mode = session.getImageGenerationMode();
        if ("off".equals(mode)) return;
        String apiKey = runtime.imageGenerationKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("已选择 AI 生图，但后台尚未配置 GPT Image 2 的 Images API Key");
        }
        Files.createDirectories(outputDir);
        int requested = requestedImageCount(mode, runtime.imageGenerationMaxImages());
        List<String> subjects = List.of("hero scene", "process or relationship", "outcome or future scene", "detail visual");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("provider", "openai-compatible-images");
        manifest.put("model", runtime.imageGenerationModel());
        manifest.put("mode", mode);
        manifest.put("images", java.util.stream.IntStream.range(0, requested).mapToObj(i -> "ai-" + (i + 1) + ".png").toList());
        for (int index = 0; index < requested; index++) {
            events.accept("authoring", Map.of("progress", 20 + index * 4,
                    "message", "正在生成 AI 视觉素材（" + (index + 1) + "/" + requested + "）"));
            byte[] image;
            try {
                image = imageClient.generate(prompt(session, subjects.get(index)), "1536x1024", runtime.imageGenerationQuality());
            } catch (OpenAiImageClient.ImageProviderException e) {
                log.error("PPT Images API 调用失败: taskId={}, imageIndex={}", session.getTaskId(), index + 1, e);
                throw new IllegalStateException("GPT Image 2 生图失败，请稍后重试");
            } catch (IOException e) {
                log.error("PPT Images API 网络或响应读取失败: taskId={}, imageIndex={}", session.getTaskId(), index + 1, e);
                throw new IllegalStateException("GPT Image 2 生图失败，请稍后重试");
            }
            validatePng(image);
            Files.write(outputDir.resolve("ai-" + (index + 1) + ".png"), image);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("manifest.json").toFile(), manifest);
        Files.writeString(outputDir.resolve("README.txt"), "Server-generated presentation assets. Use them only as visual material; do not display this file or invent source citations for them.\n");
    }

    /** Keep the billed image count and the actual bounded generation count identical. */
    public static int requestedImageCount(String mode, int maxImages) {
        int bounded = Math.max(1, Math.min(4, maxImages));
        if ("prefer".equals(mode)) return bounded;
        return "supplement".equals(mode) ? Math.min(2, bounded) : 0;
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

}
