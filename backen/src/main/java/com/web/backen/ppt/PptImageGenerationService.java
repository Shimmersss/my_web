package com.web.backen.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.settings.RuntimeConfigService;
import com.web.backen.ai.OpenAiImageClient;
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
 * Builds a durable presentation outline before generating a bounded set of slide-bound visuals.
 * It intentionally uses a separately configured Images API credential: a local
 * Codex CLI OAuth/session token is neither read nor forwarded to this endpoint.
 */
@Component
public class PptImageGenerationService {
    private static final Logger log = LoggerFactory.getLogger(PptImageGenerationService.class);
    private static final int MAX_IMAGES_PER_TASK = 10;
    private static final long MAX_IMAGE_BYTES = 12L * 1024 * 1024;
    private static final List<String> IMAGE_SUBJECTS = List.of(
            "hero scene", "process or relationship", "outcome or future scene", "detail visual",
            "context or environment", "comparison or contrast", "human workflow", "data-inspired abstract visual",
            "close-up mechanism or texture", "supporting scene");
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
        int requested = requestedImageCount(mode, runtime.imageGenerationMaxImages(), session.getRequestedImageGenerationCount());
        Path planPath = session.getTaskDir().resolve("presentation-plan.json");
        Map<String, Object> plan = preparePlan(session, requested, planPath);
        if ("off".equals(mode)) return;
        String apiKey = runtime.imageGenerationKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("已选择 AI 生图，但后台尚未配置 GPT Image 2 的 Images API Key");
        }
        Files.createDirectories(outputDir);
        @SuppressWarnings("unchecked") List<Map<String, Object>> plannedImages = (List<Map<String, Object>>) plan.get("generatedImages");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("provider", "openai-compatible-images");
        manifest.put("model", runtime.imageGenerationModel());
        manifest.put("mode", mode);
        manifest.put("taskId", session.getTaskId());
        manifest.put("outputFormat", session.getOutputFormat());
        manifest.put("images", plannedImages);
        for (int index = 0; index < requested; index++) {
            Map<String, Object> item = plannedImages.get(index);
            events.accept("authoring", Map.of("progress", 20 + index * 4,
                    "message", "正在生成 AI 视觉素材（" + (index + 1) + "/" + requested + "）"));
            byte[] image;
            try {
                image = imageClient.generate(String.valueOf(item.get("prompt")), "1536x1024", runtime.imageGenerationQuality());
            } catch (OpenAiImageClient.ImageProviderException e) {
                log.error("PPT Images API 调用失败: taskId={}, imageIndex={}", session.getTaskId(), index + 1, e);
                throw new IllegalStateException("GPT Image 2 生图失败，请稍后重试");
            } catch (IOException e) {
                log.error("PPT Images API 网络或响应读取失败: taskId={}, imageIndex={}", session.getTaskId(), index + 1, e);
                throw new IllegalStateException("GPT Image 2 生图失败，请稍后重试");
            }
            validatePng(image);
            Files.write(outputDir.resolve(String.valueOf(item.get("fileName"))), image);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("generated-image-manifest.json").toFile(), manifest);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("manifest.json").toFile(), manifest);
        Files.writeString(outputDir.resolve("README.txt"), "Server-generated presentation assets. Use them only as visual material; do not display this file or invent source citations for them.\n");
    }

    /** A small deterministic outline is retained before any billable image call. Codex receives it as a binding contract. */
    private Map<String, Object> preparePlan(PptGenerationSession session, int imageCount, Path target) throws IOException {
        if (Files.isRegularFile(target)) {
            try {
                @SuppressWarnings("unchecked") Map<String, Object> existing = objectMapper.readValue(target.toFile(), Map.class);
                Object images = existing.get("generatedImages");
                if (images instanceof List<?> list && list.size() == imageCount) return existing;
            } catch (Exception ignored) { }
        }
        int pages = session.getRequestedPageCount() > 0 ? session.getRequestedPageCount() : Math.max(3, imageCount + 2);
        List<Map<String, Object>> slides = new java.util.ArrayList<>();
        List<Map<String, Object>> generated = new java.util.ArrayList<>();
        String request = session.getPrompt() == null ? "" : session.getPrompt().replaceAll("\\s+", " ").trim();
        if (request.length() > 1400) request = request.substring(0, 1400);
        for (int page = 1; page <= pages; page++) {
            String slideId = String.format("slide-%02d", page);
            String title = page == 1 ? "封面主题" : page == pages ? "总结与下一步" : "核心要点 " + (page - 1);
            slides.add(Map.of("slideId", slideId, "index", page, "title", title,
                    "purpose", page == 1 ? "cover" : page == pages ? "closing" : "content"));
            if (page > 1 && page < pages && generated.size() < imageCount) {
                int number = generated.size() + 1;
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("id", String.format("GPT%02d", number));
                image.put("fileName", "gpt-" + number + ".png");
                image.put("slideId", slideId); image.put("slideIndex", page); image.put("slideTitle", title);
                image.put("prompt", prompt(session, title, request));
                generated.add(image);
            }
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("version", 1); plan.put("taskId", session.getTaskId()); plan.put("outputFormat", session.getOutputFormat());
        plan.put("slides", slides); plan.put("generatedImages", generated);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), plan);
        return plan;
    }

    /** Keep the billed image count and the actual bounded generation count identical. */
    public static int requestedImageCount(String mode, int maxImages) {
        return requestedImageCount(mode, maxImages, 0);
    }

    /**
     * The runtime maximum remains the hard ceiling. Supplement keeps its intentionally
     * smaller ceiling while allowing the user to choose any quantity below it.
     */
    public static int requestedImageCount(String mode, int maxImages, int requestedCount) {
        int bounded = Math.max(1, Math.min(MAX_IMAGES_PER_TASK, maxImages));
        int modeMaximum = "supplement".equals(mode) ? Math.min(2, bounded) : bounded;
        if (!"prefer".equals(mode) && !"supplement".equals(mode)) return 0;
        return requestedCount > 0 ? Math.min(modeMaximum, requestedCount) : modeMaximum;
    }

    private String prompt(PptGenerationSession session, String slideTitle, String request) {
        return "Create one polished 16:9 presentation visual for this request: " + request + ". "
                + "This image is bound only to the slide titled '" + slideTitle + "'. Focus on that specific narrative role. Match a premium editorial/infographic visual language, leave calm negative space, "
                + "and do not render any words, labels, logos, watermark, UI, equations, citations, slide border, or tiny unreadable details.";
    }

    private void validatePng(byte[] image) {
        if (image == null || image.length < 128 || image.length > MAX_IMAGE_BYTES
                || image[0] != (byte) 0x89 || image[1] != 0x50 || image[2] != 0x4e || image[3] != 0x47) {
            throw new IllegalStateException("GPT Image 2 返回的素材不是受支持的 PNG，或体积超限");
        }
    }

}
