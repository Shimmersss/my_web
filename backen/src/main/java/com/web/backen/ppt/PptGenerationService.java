package com.web.backen.ppt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.config.PptGenerationConfig;
import com.web.backen.translate.LlmService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Service
public class PptGenerationService {

    private static final Logger log = LoggerFactory.getLogger(PptGenerationService.class);
    private static final Pattern JSON_IMAGE_OBJECT = Pattern.compile("\\{\\s*\"index\"\\s*:\\s*\\d+[\\s\\S]*?\\n\\s*\\}");
    private static final Pattern ARABIC_SLIDE_COUNT = Pattern.compile("(?:生成|做|制作|输出|整理|压缩|控制|限制)?\\s*(\\d{1,2})(?:\\s*[-~到至]\\s*(\\d{1,2}))?\\s*(?:页|張|张|slides?|PPT)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_SLIDE_COUNT = Pattern.compile("([一二两三四五六七八九十]{1,3})\\s*(?:页|張|张)");
    private static final String SYSTEM_PROMPT = """
            You are an expert academic presentation designer. Create a concise, editable thesis or research presentation deck.
            Return ONLY valid JSON. Do not wrap it in Markdown.
            JSON shape:
            {
              "title": "deck title",
              "subtitle": "optional subtitle",
              "audience": "target audience",
              "theme": "short style description",
              "slides": [
                {
                  "type": "cover|contents|section|content|image|conclusion|thanks",
                  "section": "optional section label",
                  "title": "slide title",
                  "headline": "one strong sentence",
                  "bullets": ["3 to 5 short bullets"],
                  "metrics": [{"value":"0.8321","label":"Random Forest PRC"}],
                  "notes": "speaker notes",
                  "imageHint": "optional image usage hint",
                  "imageId": "optional image id from imageManifest",
                  "layout": "auto|full-image|image-left|image-right|metrics|comparison|conclusion"
                }
              ]
            }
            Rules:
            - Use Simplified Chinese unless the user's prompt explicitly requests another language.
            - If targetSlideCount is provided, return exactly that many slides including cover and thanks. Compress and merge content to fit the requested count; never satisfy a page limit by only keeping the first chapters.
            - If targetSlideCount is empty, create 16 to 20 slides for thesis defense.
            - When the requested page count is small, preserve the complete story arc by merging adjacent details into denser synthesis slides: background, method, data/experiment, result, conclusion.
            - Prefer short, presentation-ready phrasing over paragraphs. Use large claims and fewer words.
            - Preserve important technical terms, numbers, model names, datasets, and metrics.
            - Do not invent specific metrics if the paper text does not contain them.
            - Use section as a semantic uppercase label such as BACKGROUND, ROUTE, DATA, FEATURES, MODELS, RESULT, SCALE, CONCLUSION, OUTLOOK. Do not use numeric labels like 1.1 or 2.3.
            - Use the builtInTemplate palette as the single visual color source. Do not infer or invent another palette from uploaded PPTX text samples or paper images.
            - If templateStyle.frameworkMode is true, follow templateStyle.templateFramework as the preferred slide rhythm and layout skeleton. Map cover/contents/section/content/image/conclusion/thanks slides onto the closest framework roles while replacing all original wording with the new deck content.
            - If imageManifest is provided, choose relevant imageId values deliberately only for slides where the image is a proof object. Do not rotate images blindly.
            - Never pair a slide with an image unless the slide title/headline directly matches that image's title, summary, or bestUse. If no image matches, leave imageId empty.
            - Do not assign image layouts when imageId is empty. Use content, metrics, comparison, conclusion, or auto layout instead.
            - Ignore imageManifest items where useful is false or importance is below 3.
            - Use image/full-image layouts for technical routes, charts, tables, model/result figures, and important screenshots. Use text, metrics, comparison, or conclusion layouts for synthesis slides.
            - Use layout image-left, image-right, or full-image based on the chosen image's layoutHint. Prefer full-image for dense charts, tables, workflows, and architecture diagrams.
            - Use metrics when the paper contains strong numeric results; put the numeric values in metrics.
            - Use section slides as chapter dividers and keep them visually sparse.
            - For thesis defense decks, prefer the rhythm: cover, contents, section, background, route, section, data, features, models, section, 4-6 result slides, section, conclusion, outlook, thanks.
            """;
    private static final String VISION_PROMPT = """
            You are helping build a thesis defense PPT. Inspect the attached paper images in order.
            Return compact JSON only:
            {
              "images": [
                {
                  "index": 1,
                  "kind": "workflow|chart|table|architecture|formula|other",
                  "title": "短标题",
                  "summary": "15字内说明",
                  "bestUse": "15字内用途",
                  "importance": 1,
                  "useful": true,
                  "layoutHint": "full-image|image-left|image-right|none"
                }
              ]
            }
            Rules:
            - Use the provided order number as index.
            - Keep every field short to avoid truncation.
            - Be concrete: mention visible chart/table/workflow/model/result content.
            - Mark useful=false for logos, decorative screenshots, blank/mostly text pages, repeated pages, tiny formulas, unreadable crops, or images that do not provide slide evidence.
            - importance is 1 to 5, where 5 means highly useful for the deck.
            - layoutHint: full-image for dense charts/tables/workflows/architecture; image-left or image-right for simple figures that can sit beside text; none for useless images.
            """;
    private static final String REVISE_PROMPT = """
            You revise an existing presentation deck JSON according to the user's instruction.
            Return ONLY valid JSON with the same shape. Do not wrap it in Markdown.
            Preserve useful existing content unless the user asks to change it.
            Keep slides concise and presentation-ready.
            """;
    private static final String SLIDE_COUNT_REPAIR_PROMPT = SYSTEM_PROMPT + """

            You are correcting a deck that missed the requested slide count.
            Return a revised deck with exactly requiredSlideCount slides, including cover and thanks.
            Preserve the full story arc by merging or expanding content across the whole deck. Do not keep only the first slides.
            """;
    private static final String COMPACT_JSON_RETRY_PROMPT = SYSTEM_PROMPT + """

            The previous response was truncated before the JSON closed.
            Return a smaller but complete JSON object.
            Compression rules:
            - Keep exactly targetSlideCount slides when targetSlideCount is present.
            - Each slide has at most 3 bullets.
            - Each bullet is at most 18 Chinese characters.
            - notes is at most 35 Chinese characters.
            - Prefer empty metrics and imageId unless the evidence is essential.
            - Close every array and object. Valid complete JSON is more important than detail.
            """;

    private final PptGenerationConfig config;
    private final PptInputExtractor inputExtractor;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, PptGenerationSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private final SecureRandom secureRandom = new SecureRandom();
    private Path storageDir;

    public PptGenerationService(PptGenerationConfig config, PptInputExtractor inputExtractor,
                                LlmService llmService, ObjectMapper objectMapper) {
        this.config = config;
        this.inputExtractor = inputExtractor;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, config.getQueueCapacity())),
                runnable -> {
                    Thread thread = new Thread(runnable, "ppt-generation-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PostConstruct
    public void initialize() throws IOException {
        storageDir = Path.of(config.getStorageDir()).toAbsolutePath().normalize();
        Files.createDirectories(storageDir);
        loadRecentSessions();
        cleanupHistory();
        log.info("PPT 生成队列已启动: workers=1, queueCapacity={}, maxHistory={}, storage={}",
                config.getQueueCapacity(), config.getMaxHistory(), storageDir);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public List<Map<String, Object>> templates() {
        return List.of(
                Map.of("key", "academic-blue", "name", "学术蓝", "description", "正式、清爽，适合论文答辩", "palette", List.of("005BAC", "063A78", "D9A441", "EFF6FF", "1F2937")),
                Map.of("key", "minimal-ink", "name", "极简黑白", "description", "高对比、少装饰，适合技术分享", "palette", List.of("111827", "374151", "0EA5E9", "F8FAFC", "1F2937")),
                Map.of("key", "emerald-report", "name", "数据绿", "description", "沉稳、偏报告感，适合项目汇报", "palette", List.of("047857", "064E3B", "F59E0B", "ECFDF5", "1F2937")),
                Map.of("key", "warm-defense", "name", "暖色答辩", "description", "温和醒目，适合毕业答辩", "palette", List.of("B45309", "7C2D12", "2563EB", "FFF7ED", "1F2937"))
        );
    }

    public PptGenerationSession createTask(String prompt, String templateKey, String templateMode, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile paperFile) throws IOException {
        String cleanPrompt = validatePrompt(prompt);
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        Path taskDir = Files.createDirectories(storageDir.resolve(taskId));
        PptGenerationSession session = new PptGenerationSession(taskId, cleanPrompt, taskDir);
        session.setAccessToken(newAccessToken());
        session.setTemplateKey(normalizeTemplateKey(templateKey));
        session.setTemplateMode(normalizeTemplateMode(templateMode));
        session.setExtractionPercent(clamp(extractionPercent, 10, 100));

        try {
            if (templateFile != null && !templateFile.isEmpty()) {
                validateFile(templateFile, ".pptx", config.getMaxTemplateBytes(), "PPT 模板");
                session.setTemplateFileName(templateFile.getOriginalFilename());
                copyUpload(templateFile, session.getTemplatePath());
            }
            if ("template-fill".equals(session.getTemplateMode()) && !hasTemplate(session)) {
                throw new IllegalArgumentException("母版填充模式需要先上传 PPTX 模板");
            }
            if (paperFile != null && !paperFile.isEmpty()) {
                validatePaperFile(paperFile);
                session.setPaperFileName(paperFile.getOriginalFilename());
                copyUpload(paperFile, session.getPaperPath());
            }
            session.setOutputFileName("AI生成PPT-" + taskId + ".pptx");
            session.setStatus("queued");
            session.setProgressStage("queued");
            sessions.put(taskId, session);
            saveMetadata(session);
            executor.execute(() -> runOutlineTask(session));
            updateQueuePositions();
            emit(session, "queued", Map.of("message", "任务已进入大纲生成队列", "queuePosition", session.getQueuePosition()));
            return session;
        } catch (RejectedExecutionException e) {
            sessions.remove(taskId);
            deleteRecursively(taskDir);
            throw new IllegalStateException("PPT 生成队列已满，请稍后再试");
        } catch (Exception e) {
            sessions.remove(taskId);
            deleteRecursively(taskDir);
            if (e instanceof IOException io) throw io;
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    PptGenerationSession renderTask(String taskId, JsonNode deck) {
        return renderTask(taskId, deck, null, null);
    }

    PptGenerationSession renderTask(String taskId, JsonNode deck, String templateMode, Integer extractionPercent) {
        PptGenerationSession session = requireSession(taskId);
        return renderTask(session, deck, templateMode, extractionPercent);
    }

    public PptGenerationSession renderTask(String taskId, String accessToken, JsonNode deck,
                                           String templateMode, Integer extractionPercent) {
        return renderTask(requireAuthorizedSession(taskId, accessToken), deck, templateMode, extractionPercent);
    }

    private PptGenerationSession renderTask(PptGenerationSession session, JsonNode deck,
                                            String templateMode, Integer extractionPercent) {
        validateDeck(deck);
        synchronized (session) {
            ensureTaskIdle(session);
            String previousStatus = session.getStatus();
            String previousStage = session.getProgressStage();
            String previousError = session.getErrorMessage();
            double previousProgress = session.getProgress();
            int previousExtractionPercent = session.getExtractionPercent();
            String previousTemplateMode = session.getTemplateMode();
            byte[] previousDeck = readOptionalBytes(session.getDeckJsonPath());
            try {
            if (templateMode != null && !templateMode.isBlank()) {
                session.setTemplateMode(normalizeTemplateMode(templateMode));
            }
            if (extractionPercent != null) {
                session.setExtractionPercent(clamp(extractionPercent, 10, 100));
            }
            if ("template-fill".equals(session.getTemplateMode()) && !hasTemplate(session)) {
                throw new IllegalArgumentException("母版填充模式需要先上传 PPTX 模板");
            }
            Files.writeString(session.getDeckJsonPath(),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(deck), StandardCharsets.UTF_8);
            session.setStatus("queued");
            session.setProgress(0);
            session.setProgressStage("queued");
            session.setErrorMessage(null);
            saveMetadata(session);
            executor.execute(() -> runRenderTask(session));
            updateQueuePositions();
            emit(session, "queued", Map.of("message", "任务已进入 PPTX 生成队列", "queuePosition", session.getQueuePosition()));
            return session;
            } catch (RejectedExecutionException e) {
                restoreDeck(session.getDeckJsonPath(), previousDeck);
                restoreSessionState(session, previousStatus, previousStage, previousError, previousProgress,
                        previousTemplateMode, previousExtractionPercent);
                saveMetadata(session);
                throw new IllegalStateException("PPT 生成队列已满，请稍后再试");
            } catch (IOException e) {
                restoreDeck(session.getDeckJsonPath(), previousDeck);
                restoreSessionState(session, previousStatus, previousStage, previousError, previousProgress,
                        previousTemplateMode, previousExtractionPercent);
                saveMetadata(session);
                throw new IllegalStateException("保存 PPT 大纲失败: " + e.getMessage(), e);
            }
        }
    }

    JsonNode updateDeck(String taskId, JsonNode deck) {
        return updateDeck(requireSession(taskId), deck);
    }

    public JsonNode updateDeck(String taskId, String accessToken, JsonNode deck) {
        return updateDeck(requireAuthorizedSession(taskId, accessToken), deck);
    }

    private JsonNode updateDeck(PptGenerationSession session, JsonNode deck) {
        validateDeck(deck);
        synchronized (session) {
            ensureTaskIdle(session);
            try {
            Files.writeString(session.getDeckJsonPath(),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(deck), StandardCharsets.UTF_8);
            session.setStatus("outline_ready");
            session.setProgress(50);
            session.setProgressStage("outline_ready");
            saveMetadata(session);
            return deck;
            } catch (IOException e) {
                throw new IllegalStateException("保存 PPT 大纲失败: " + e.getMessage(), e);
            }
        }
    }

    JsonNode reviseDeck(String taskId, String instruction, JsonNode deck) throws IOException {
        return reviseDeck(requireSession(taskId), instruction, deck);
    }

    public JsonNode reviseDeck(String taskId, String accessToken, String instruction, JsonNode deck) throws IOException {
        return reviseDeck(requireAuthorizedSession(taskId, accessToken), instruction, deck);
    }

    private JsonNode reviseDeck(PptGenerationSession session, String instruction, JsonNode deck) throws IOException {
        if (instruction == null || instruction.isBlank()) throw new IllegalArgumentException("请输入修改要求");
        JsonNode source = deck == null || deck.isNull() ? getDeck(session) : deck;
        validateDeck(source);
        String previousStatus;
        String previousStage;
        double previousProgress;
        synchronized (session) {
            ensureTaskIdle(session);
            previousStatus = session.getStatus();
            previousStage = session.getProgressStage();
            previousProgress = session.getProgress();
            session.setStatus("revising");
            session.setProgressStage("revising");
            saveMetadata(session);
        }
        Future<JsonNode> future;
        try {
            future = executor.submit(() -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("instruction", instruction.trim());
                payload.put("currentDeck", source);
                String response = llmService.complete(REVISE_PROMPT,
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload),
                        config.getLlmMaxTokens());
                JsonNode revised = parseLlmJson(response);
                validateDeck(revised);
                synchronized (session) {
                    if (!"revising".equals(session.getStatus())) {
                        throw new CancellationException("PPT 大纲修改已取消");
                    }
                    Files.writeString(session.getDeckJsonPath(),
                            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(revised), StandardCharsets.UTF_8);
                    session.setStatus("outline_ready");
                    session.setProgress(50);
                    session.setProgressStage("outline_ready");
                    session.setErrorMessage(null);
                    saveMetadata(session);
                }
                return revised;
            });
        } catch (RejectedExecutionException e) {
            restoreRevisionState(session, previousStatus, previousStage, previousProgress);
            throw new IllegalStateException("PPT 生成队列已满，请稍后再试");
        }
        try {
            return future.get(Math.max(30, config.getTimeoutSeconds()), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            restoreRevisionState(session, previousStatus, previousStage, previousProgress);
            throw new IllegalStateException("PPT 大纲修改被中断", e);
        } catch (TimeoutException e) {
            future.cancel(true);
            restoreRevisionState(session, previousStatus, previousStage, previousProgress);
            throw new IllegalStateException("PPT 大纲修改超时", e);
        } catch (ExecutionException e) {
            restoreRevisionState(session, previousStatus, previousStage, previousProgress);
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("PPT 大纲修改失败", cause);
        }
    }

    JsonNode getDeck(String taskId) {
        PptGenerationSession session = requireSession(taskId);
        return getDeck(session);
    }

    public JsonNode getDeck(String taskId, String accessToken) {
        return getDeck(requireAuthorizedSession(taskId, accessToken));
    }

    private JsonNode getDeck(PptGenerationSession session) {
        if (!Files.isRegularFile(session.getDeckJsonPath())) {
            throw new IllegalStateException("PPT 大纲尚未生成");
        }
        try {
            return objectMapper.readTree(session.getDeckJsonPath().toFile());
        } catch (IOException e) {
            throw new IllegalStateException("读取 PPT 大纲失败: " + e.getMessage(), e);
        }
    }

    PptGenerationSession getSession(String taskId) {
        updateQueuePositions();
        return sessions.get(taskId);
    }

    public PptGenerationSession getAuthorizedSession(String taskId, String accessToken) {
        updateQueuePositions();
        return requireAuthorizedSession(taskId, accessToken);
    }

    public List<PptGenerationSession> getRecentSessions(String accessTokens) {
        updateQueuePositions();
        Set<String> allowedTokens = parseAccessTokens(accessTokens);
        if (allowedTokens.isEmpty()) return List.of();
        return sessions.values().stream()
                .filter(session -> allowedTokens.contains(session.getAccessToken()))
                .sorted(Comparator.comparingLong(PptGenerationSession::getCreatedAt).reversed())
                .limit(Math.max(1, config.getMaxHistory()))
                .toList();
    }

    Path getOutput(String taskId) {
        PptGenerationSession session = requireSession(taskId);
        return getOutput(session);
    }

    public Path getOutput(String taskId, String accessToken) {
        return getOutput(requireAuthorizedSession(taskId, accessToken));
    }

    private Path getOutput(PptGenerationSession session) {
        if (!"completed".equals(session.getStatus())) {
            throw new IllegalStateException("PPT 尚未生成完成");
        }
        Path output = session.getOutputPath();
        if (!Files.isRegularFile(output)) {
            throw new IllegalStateException("PPT 文件不存在，请重新生成");
        }
        return output;
    }

    public void subscribe(String taskId, String accessToken, SseEmitter emitter) {
        PptGenerationSession session;
        try {
            session = requireAuthorizedSession(taskId, accessToken);
        } catch (IllegalArgumentException e) {
            sendAndComplete(emitter, "task-error", Map.of("message", "任务不存在"));
            return;
        }
        emitters.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> removeEmitter(taskId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        sendSnapshot(session, emitter);
    }

    private void runOutlineTask(PptGenerationSession session) {
        session.setStatus("outlining");
        session.setQueuePosition(0);
        saveAndProgress(session, 8, "extracting", "正在读取论文和模板");
        updateQueuePositions();

        try {
            Files.createDirectories(session.getImagesDir());
            String paperText = inputExtractor.extractPaperText(
                    hasPaper(session) ? session.getPaperPath() : null,
                    session.getPaperFileName(),
                    session.getImagesDir(),
                    session.getExtractionPercent());
            inputExtractor.extractTemplateStyle(hasTemplate(session) ? session.getTemplatePath() : null,
                    session.getTaskDir(), session.getExtractionPercent());
            applyBuiltInTemplateStyle(session);
            List<String> imagePaths = inputExtractor.listImagePaths(session.getImagesDir());
            JsonNode imageManifest = buildImageManifest(session, imagePaths);
            saveAssetCache(session);

            saveAndProgress(session, 35, "planning", "正在调用 mimo 生成 PPT 大纲");
            String deckJson = buildDeckJson(session, paperText, imageManifest);
            Files.writeString(session.getDeckJsonPath(), deckJson, StandardCharsets.UTF_8);

            session.setStatus("outline_ready");
            session.setProgress(50);
            session.setProgressStage("outline_ready");
            saveMetadata(session);
            emit(session, "outline-ready", Map.of("taskId", session.getTaskId(), "deck", objectMapper.readTree(deckJson)));
            completeEmitters(session.getTaskId());
        } catch (Exception e) {
            failTask(session, e);
        } finally {
            cleanupHistory();
            updateQueuePositions();
        }
    }

    private void runRenderTask(PptGenerationSession session) {
        session.setStatus("rendering");
        session.setQueuePosition(0);
        saveAndProgress(session, 65, canReuseExtractedAssets(session) ? "reusing_assets" : "refreshing_assets",
                canReuseExtractedAssets(session) ? "正在复用已抽取素材" : "正在刷新论文和模板素材");
        updateQueuePositions();

        try {
            List<String> imagePaths;
            if (canReuseExtractedAssets(session)) {
                imagePaths = inputExtractor.listImagePaths(session.getImagesDir());
            } else {
                if (hasPaper(session)) {
                    inputExtractor.extractPaperText(session.getPaperPath(), session.getPaperFileName(),
                            session.getImagesDir(), session.getExtractionPercent());
                }
                inputExtractor.extractTemplateStyle(hasTemplate(session) ? session.getTemplatePath() : null,
                        session.getTaskDir(), session.getExtractionPercent());
                applyBuiltInTemplateStyle(session);
                imagePaths = inputExtractor.listImagePaths(session.getImagesDir());
                buildImageManifest(session, imagePaths);
                saveAssetCache(session);
            }
            JsonNode renderDeck = objectMapper.readTree(session.getDeckJsonPath().toFile());
            JsonNode renderManifest = objectMapper.readTree(session.getImageManifestPath().toFile());
            JsonNode assignedDeck = ensureImageAssignments(renderDeck, renderManifest);
            Files.writeString(session.getDeckJsonPath(),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(assignedDeck), StandardCharsets.UTF_8);
            saveAndProgress(session, 70, "rendering", "正在生成可编辑 PPTX");
            runNodeGenerator(session, imagePaths);

            session.setStatus("completed");
            session.setProgress(100);
            session.setProgressStage("completed");
            session.setCompletedAt(System.currentTimeMillis());
            saveMetadata(session);
            emit(session, "done", Map.of("taskId", session.getTaskId()));
            completeEmitters(session.getTaskId());
        } catch (Exception e) {
            failTask(session, e);
        } finally {
            cleanupHistory();
            updateQueuePositions();
        }
    }

    private String buildDeckJson(PptGenerationSession session, String paperText, JsonNode imageManifest) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userPrompt", session.getPrompt());
        payload.put("paperFileName", session.getPaperFileName() == null ? "" : session.getPaperFileName());
        payload.put("paperText", paperText == null ? "" : paperText);
        payload.put("templateFileName", session.getTemplateFileName() == null ? "" : session.getTemplateFileName());
        payload.put("templateMode", session.getTemplateMode());
        payload.put("extractionPercent", session.getExtractionPercent());
        payload.put("templateStyle", objectMapper.readValue(session.getStyleJsonPath().toFile(), Map.class));
        payload.put("builtInTemplate", templateByKey(session.getTemplateKey()));
        Optional<Integer> targetSlideCount = extractTargetSlideCount(session.getPrompt());
        payload.put("targetSlideCount", targetSlideCount.orElse(null));
        payload.put("slideCountPolicy", "If targetSlideCount is present, return exactly that many slides. Compress and merge across the whole paper instead of truncating early sections.");
        payload.put("imageManifest", imageManifest == null || imageManifest.isNull() ? Map.of("images", List.of()) : imageManifest);
        String payloadJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        int maxTokens = deckMaxTokens(targetSlideCount);
        String response = llmService.complete(SYSTEM_PROMPT, payloadJson, maxTokens);
        JsonNode root;
        try {
            root = parseLlmJson(response);
        } catch (Exception firstError) {
            if (!looksLikeTruncatedJson(response, firstError)) {
                if (firstError instanceof IOException io) throw io;
                throw firstError;
            }
            log.warn("PPT 大纲 JSON 疑似被截断，使用紧凑格式重试: taskId={}, maxTokens={}, error={}",
                    session.getTaskId(), maxTokens, firstError.getMessage());
            Map<String, Object> retryPayload = new LinkedHashMap<>(payload);
            retryPayload.put("retryReason", "Previous response was truncated. Return compact complete JSON only.");
            retryPayload.put("outputBudget", "Use short titles, max 3 bullets per slide, short notes, and close the JSON.");
            String retryResponse = llmService.complete(COMPACT_JSON_RETRY_PROMPT,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(retryPayload),
                    Math.min(32768, maxTokens + 4096));
            root = parseLlmJson(retryResponse);
        }
        root = repairSlideCountIfNeeded(payload, root, targetSlideCount);
        root = ensureImageAssignments(root, imageManifest);
        if (!root.hasNonNull("slides") || !root.get("slides").isArray() || root.get("slides").isEmpty()) {
            throw new IllegalStateException("mimo 返回的 PPT 结构缺少 slides");
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private JsonNode repairSlideCountIfNeeded(Map<String, Object> originalPayload, JsonNode deck, Optional<Integer> targetSlideCount) throws IOException {
        if (targetSlideCount.isEmpty()) return deck;
        JsonNode slides = deck == null ? null : deck.get("slides");
        int currentCount = slides != null && slides.isArray() ? slides.size() : 0;
        int requiredCount = targetSlideCount.get();
        if (currentCount == requiredCount) return deck;

        Map<String, Object> repairPayload = new LinkedHashMap<>(originalPayload);
        repairPayload.put("requiredSlideCount", requiredCount);
        repairPayload.put("previousSlideCount", currentCount);
        repairPayload.put("currentDeck", deck);
        repairPayload.put("repairInstruction", "Revise currentDeck to exactly requiredSlideCount slides. Merge, compress, or expand across the whole paper; do not truncate early sections.");
        String response = llmService.complete(SLIDE_COUNT_REPAIR_PROMPT,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(repairPayload),
                deckMaxTokens(targetSlideCount));
        JsonNode repaired = parseLlmJson(response);
        JsonNode repairedSlides = repaired == null ? null : repaired.get("slides");
        int repairedCount = repairedSlides != null && repairedSlides.isArray() ? repairedSlides.size() : 0;
        if (repairedCount != requiredCount) {
            log.warn("PPT 大纲页数纠偏后仍不匹配: required={}, actual={}", requiredCount, repairedCount);
        }
        return repaired;
    }

    private JsonNode buildImageManifest(PptGenerationSession session, List<String> imagePaths) {
        List<Path> paths = imagePaths == null ? List.of() : imagePaths.stream()
                .filter(Objects::nonNull)
                .map(Path::of)
                .filter(Files::isRegularFile)
                .limit(Math.max(0, config.getMaxVisionImages()))
                .toList();
        JsonNode fallback = fallbackImageManifest(paths);
        if (paths.isEmpty()) {
            writeImageManifest(session, fallback);
            return fallback;
        }
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("Paper file: ").append(session.getPaperFileName() == null ? "" : session.getPaperFileName()).append('\n');
            prompt.append("User request: ").append(session.getPrompt()).append('\n');
            prompt.append("Attached images correspond to these ids in order:\n");
            for (int i = 0; i < paths.size(); i++) {
                prompt.append(i + 1).append(". ").append(imageId(paths.get(i))).append(" - ")
                        .append(paths.get(i).getFileName()).append('\n');
            }
            String response = llmService.completeWithImages(config.getVisionModel(), VISION_PROMPT,
                    prompt.toString(), paths, config.getVisionMaxTokens());
            JsonNode root = parseVisionManifestResponse(response);
            JsonNode normalized = normalizeImageManifest(paths, root);
            writeImageManifest(session, normalized);
            return normalized;
        } catch (Exception e) {
            log.warn("PPT 图片视觉摘要失败，使用启发式 manifest: taskId={}, model={}",
                    session.getTaskId(), config.getVisionModel(), e);
            writeImageManifest(session, fallback);
            return fallback;
        }
    }

    private JsonNode parseVisionManifestResponse(String response) throws IOException {
        try {
            return parseLlmJson(response);
        } catch (Exception firstError) {
            String text = response == null ? "" : response;
            Matcher matcher = JSON_IMAGE_OBJECT.matcher(text);
            List<JsonNode> recovered = new ArrayList<>();
            while (matcher.find()) {
                try {
                    recovered.add(objectMapper.readTree(matcher.group()));
                } catch (Exception ignored) {
                    // Skip incomplete object fragments.
                }
            }
            if (!recovered.isEmpty()) {
                return objectMapper.valueToTree(Map.of("images", recovered));
            }
            if (firstError instanceof IOException io) throw io;
            throw new IOException(firstError.getMessage(), firstError);
        }
    }

    private JsonNode fallbackImageManifest(List<Path> paths) {
        List<Map<String, Object>> images = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            Path path = paths.get(i);
            String filename = path.getFileName().toString();
            boolean tableImage = filename.startsWith("paper-table-") || filename.startsWith("paper-excel-");
            images.add(Map.of(
                    "id", imageId(path),
                    "index", i + 1,
                    "filename", filename,
                    "kind", tableImage ? "table" : "other",
                    "title", tableImage ? "论文表格 " + (i + 1) : "论文图片 " + (i + 1),
                    "summary", tableImage ? "从论文 DOCX 表格渲染的图片" : "从论文附件中提取的图片素材",
                    "bestUse", tableImage ? "结果或数据说明页" : "结合相邻论文内容作为图文页素材",
                    "importance", tableImage ? 4 : 2,
                    "useful", tableImage,
                    "layoutHint", tableImage ? "full-image" : "none"));
        }
        return objectMapper.valueToTree(Map.of("images", images));
    }

    private JsonNode normalizeImageManifest(List<Path> paths, JsonNode root) {
        Map<Integer, JsonNode> byIndex = new HashMap<>();
        JsonNode imagesNode = root == null ? null : root.get("images");
        if (imagesNode != null && imagesNode.isArray()) {
            for (JsonNode item : imagesNode) {
                int index = item.path("index").asInt(0);
                if (index > 0) byIndex.put(index, item);
            }
        }
        List<Map<String, Object>> images = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            int index = i + 1;
            Path path = paths.get(i);
            JsonNode item = byIndex.get(index);
            boolean trustedTable = path.getFileName().toString().startsWith("paper-table-")
                    || path.getFileName().toString().startsWith("paper-excel-");
            int importance = clamp(item == null ? 3 : item.path("importance").asInt(3), 1, 5);
            boolean useful = item == null || item.path("useful").asBoolean(importance >= 3);
            if (trustedTable) {
                importance = Math.max(3, importance);
                useful = true;
            }
            String layoutHint = normalizeLayoutHint(textOr(item, "layoutHint", importance >= 4 ? "full-image" : "image-right"));
            if (trustedTable) layoutHint = "full-image";
            if (!useful || importance < 3) {
                layoutHint = "none";
            }
            images.add(Map.of(
                    "id", imageId(path),
                    "index", index,
                    "filename", path.getFileName().toString(),
                    "kind", textOr(item, "kind", "other"),
                    "title", textOr(item, "title", "论文图片 " + index),
                    "summary", textOr(item, "summary", "从论文附件中提取的图片素材"),
                    "bestUse", textOr(item, "bestUse", "结合相邻论文内容作为图文页素材"),
                    "importance", importance,
                    "useful", useful,
                    "layoutHint", layoutHint));
        }
        return objectMapper.valueToTree(Map.of("images", images));
    }

    private JsonNode ensureImageAssignments(JsonNode deck, JsonNode imageManifest) {
        if (deck == null || !deck.isObject() || imageManifest == null) return deck;
        JsonNode slides = deck.path("slides");
        JsonNode images = imageManifest.path("images");
        if (!slides.isArray() || !images.isArray()) return deck;
        List<JsonNode> candidates = StreamSupport.stream(images.spliterator(), false)
                .filter(image -> image.path("useful").asBoolean(false) && image.path("importance").asInt(0) >= 3)
                .toList();
        Set<Integer> usedSlides = new HashSet<>();
        Set<String> assignedIds = new HashSet<>();
        for (int index = 0; index < slides.size(); index++) {
            String imageId = slides.get(index).path("imageId").asText("");
            if (!imageId.isBlank()) {
                usedSlides.add(index);
                assignedIds.add(imageId);
            }
        }
        for (JsonNode image : candidates) {
            if (assignedIds.contains(image.path("id").asText())) continue;
            int slideIndex = bestSlideForImage(slides, image, usedSlides);
            if (slideIndex < 0) continue;
            JsonNode slide = slides.get(slideIndex);
            if (slide instanceof com.fasterxml.jackson.databind.node.ObjectNode objectSlide) {
                objectSlide.put("imageId", image.path("id").asText());
                objectSlide.put("imageHint", textOr(image, "bestUse", textOr(image, "title", "论文素材")));
                objectSlide.put("layout", normalizeLayoutHint(image.path("layoutHint").asText("image-right")));
                usedSlides.add(slideIndex);
            }
        }
        return deck;
    }

    private int bestSlideForImage(JsonNode slides, JsonNode image, Set<Integer> usedSlides) {
        boolean table = "table".equalsIgnoreCase(image.path("kind").asText())
                || image.path("filename").asText().startsWith("paper-table-")
                || image.path("filename").asText().startsWith("paper-excel-");
        int bestIndex = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int index = 0; index < slides.size(); index++) {
            if (usedSlides.contains(index)) continue;
            JsonNode slide = slides.get(index);
            String type = slide.path("type").asText("content");
            if (!Set.of("content", "image", "conclusion").contains(type)) continue;
            String text = (slide.path("section").asText("") + " " + slide.path("title").asText("") + " "
                    + slide.path("headline").asText("")).toLowerCase(Locale.ROOT);
            int score = 0;
            if (table && containsAny(text, "result", "data", "experiment", "结果", "数据", "实验", "对比", "性能", "特征")) score += 6;
            if (!table && containsAny(text, "method", "route", "model", "background", "方法", "路线", "模型", "背景", "结构")) score += 5;
            if ("image".equals(type)) score += 3;
            if ("content".equals(type)) score += 1;
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        return bestScore > 0 ? bestIndex : -1;
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    private String normalizeLayoutHint(String value) {
        return switch (value == null ? "" : value.trim()) {
            case "full-image", "image-left", "image-right", "none" -> value.trim();
            default -> "image-right";
        };
    }

    private String imageId(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String textOr(JsonNode node, String field, String fallback) {
        if (node == null) return fallback;
        String value = node.path(field).asText("");
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void writeImageManifest(PptGenerationSession session, JsonNode manifest) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(session.getImageManifestPath().toFile(), manifest);
        } catch (IOException e) {
            log.warn("保存 PPT 图片 manifest 失败: taskId={}", session.getTaskId(), e);
        }
    }

    private boolean canReuseExtractedAssets(PptGenerationSession session) {
        if (!Files.isRegularFile(session.getAssetCachePath())
                || !Files.isRegularFile(session.getStyleJsonPath())
                || !Files.isRegularFile(session.getImageManifestPath())) {
            return false;
        }
        try {
            JsonNode cached = objectMapper.readTree(session.getAssetCachePath().toFile());
            JsonNode current = objectMapper.valueToTree(assetCacheFingerprint(session));
            return cached.equals(current);
        } catch (Exception e) {
            return false;
        }
    }

    private void saveAssetCache(PptGenerationSession session) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(session.getAssetCachePath().toFile(), assetCacheFingerprint(session));
        } catch (IOException e) {
            log.warn("保存 PPT 素材缓存指纹失败: taskId={}", session.getTaskId(), e);
        }
    }

    private Map<String, Object> assetCacheFingerprint(PptGenerationSession session) throws IOException {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("version", 3);
        fingerprint.put("templateMode", session.getTemplateMode());
        fingerprint.put("extractionPercent", session.getExtractionPercent());
        fingerprint.put("paper", fileFingerprint(hasPaper(session) ? session.getPaperPath() : null, session.getPaperFileName()));
        fingerprint.put("template", fileFingerprint(hasTemplate(session) ? session.getTemplatePath() : null, session.getTemplateFileName()));
        return fingerprint;
    }

    private Map<String, Object> fileFingerprint(Path path, String fileName) throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("fileName", fileName == null ? "" : fileName);
        if (path != null && Files.isRegularFile(path)) {
            value.put("size", String.valueOf(Files.size(path)));
            value.put("modifiedAt", String.valueOf(Files.getLastModifiedTime(path).toMillis()));
        } else {
            value.put("size", "0");
            value.put("modifiedAt", "0");
        }
        return value;
    }

    private void runNodeGenerator(PptGenerationSession session, List<String> imagePaths) throws IOException, InterruptedException {
        Path runner = Path.of(config.getRunnerScript());
        if (!runner.isAbsolute()) runner = Path.of("").toAbsolutePath().resolve(runner).normalize();
        if (!Files.isRegularFile(runner)) {
            throw new IllegalStateException("PPT 生成脚本不存在: " + runner);
        }
        Path imagesJson = session.getTaskDir().resolve("images.json");
        objectMapper.writeValue(imagesJson.toFile(), imagePaths);

        List<String> command = new ArrayList<>();
        command.add(config.getNodeCommand());
        command.add(runner.toString());
        command.add("--deck");
        command.add(session.getDeckJsonPath().toString());
        command.add("--style");
        command.add(session.getStyleJsonPath().toString());
        command.add("--images");
        command.add(imagesJson.toString());
        command.add("--manifest");
        command.add(session.getImageManifestPath().toString());
        command.add("--mode");
        command.add(session.getTemplateMode());
        if (hasTemplate(session)) {
            command.add("--template");
            command.add(session.getTemplatePath().toString());
        }
        command.add("--out");
        command.add(session.getOutputPath().toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(runner.getParent().toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
            try (InputStream input = process.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return e.getMessage();
            }
        });

        boolean exited = process.waitFor(Math.max(30, config.getTimeoutSeconds()), TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new IllegalStateException("PPT 生成脚本超时");
        }
        String output = outputFuture.orTimeout(5, TimeUnit.SECONDS).exceptionally(e -> "").join();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("PPT 生成脚本失败: " + trimLog(output));
        }
        if (!Files.isRegularFile(session.getOutputPath())) {
            throw new IllegalStateException("PPT 生成脚本未输出文件: " + trimLog(output));
        }
    }

    private void saveAndProgress(PptGenerationSession session, double progress, String stage, String message) {
        session.setProgress(progress);
        session.setProgressStage(stage);
        saveMetadata(session);
        emit(session, "progress", Map.of(
                "progress", progress,
                "stage", stage,
                "stageLabel", stageLabel(stage),
                "message", message));
    }

    private void failTask(PptGenerationSession session, Exception e) {
        log.error("PPT 生成任务失败: taskId={}", session.getTaskId(), e);
        session.setStatus("error");
        session.setProgressStage("error");
        session.setErrorMessage(e.getMessage() == null ? "PPT 生成失败" : e.getMessage());
        saveMetadata(session);
        emit(session, "task-error", Map.of("message", session.getErrorMessage()));
        completeEmitters(session.getTaskId());
    }

    private String validatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("请输入 PPT 生成提示词");
        }
        String value = prompt.trim();
        if (value.length() > Math.max(100, config.getMaxPromptChars())) {
            throw new IllegalArgumentException("提示词超过 " + config.getMaxPromptChars() + " 字限制");
        }
        return value;
    }

    private String normalizeTemplateKey(String templateKey) {
        String value = templateKey == null || templateKey.isBlank() ? "academic-blue" : templateKey.trim();
        return templates().stream().anyMatch(item -> value.equals(item.get("key"))) ? value : "academic-blue";
    }

    private String normalizeTemplateMode(String templateMode) {
        String value = templateMode == null || templateMode.isBlank() ? "framework" : templateMode.trim();
        return "template-fill".equals(value) ? "template-fill" : "framework";
    }

    private Map<String, Object> templateByKey(String templateKey) {
        String key = normalizeTemplateKey(templateKey);
        return templates().stream()
                .filter(item -> key.equals(item.get("key")))
                .findFirst()
                .orElse(templates().get(0));
    }

    private void applyBuiltInTemplateStyle(PptGenerationSession session) throws IOException {
        Map<String, Object> style = objectMapper.readValue(session.getStyleJsonPath().toFile(), Map.class);
        Map<String, Object> builtIn = templateByKey(session.getTemplateKey());
        style.put("palette", builtIn.get("palette"));
        style.put("paletteSource", "built-in-template");
        style.put("uploadedTemplateUsage", "assets-and-text-samples-only");
        style.put("builtInTemplateKey", builtIn.get("key"));
        style.put("builtInTemplateName", builtIn.get("name"));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(session.getStyleJsonPath().toFile(), style);
    }

    private void validateDeck(JsonNode deck) {
        if (deck == null || !deck.isObject()) throw new IllegalArgumentException("PPT 大纲格式错误");
        JsonNode slides = deck.get("slides");
        if (slides == null || !slides.isArray() || slides.isEmpty()) {
            throw new IllegalArgumentException("PPT 大纲至少需要 1 页");
        }
        if (slides.size() > 40) throw new IllegalArgumentException("PPT 大纲页数超过 40 页限制");
    }

    private Optional<Integer> extractTargetSlideCount(String prompt) {
        if (prompt == null || prompt.isBlank()) return Optional.empty();
        Matcher arabic = ARABIC_SLIDE_COUNT.matcher(prompt);
        if (arabic.find()) {
            String upper = arabic.group(2);
            return Optional.of(clamp(Integer.parseInt(upper == null ? arabic.group(1) : upper), 3, 40));
        }
        Matcher chinese = CHINESE_SLIDE_COUNT.matcher(prompt);
        if (chinese.find()) {
            int value = parseChineseNumber(chinese.group(1));
            if (value > 0) return Optional.of(clamp(value, 3, 40));
        }
        return Optional.empty();
    }

    private int deckMaxTokens(Optional<Integer> targetSlideCount) {
        int configured = Math.max(4096, config.getLlmMaxTokens());
        int slideCount = targetSlideCount.orElse(20);
        int estimated = 4096 + clamp(slideCount, 3, 40) * 700;
        return Math.min(32768, Math.max(configured, estimated));
    }

    private int parseChineseNumber(String value) {
        if (value == null || value.isBlank()) return 0;
        Map<Character, Integer> digits = Map.of(
                '一', 1, '二', 2, '两', 2, '三', 3, '四', 4,
                '五', 5, '六', 6, '七', 7, '八', 8, '九', 9
        );
        String text = value.trim();
        if ("十".equals(text)) return 10;
        int tenIndex = text.indexOf('十');
        if (tenIndex >= 0) {
            int tens = tenIndex == 0 ? 1 : digits.getOrDefault(text.charAt(0), 0);
            int ones = tenIndex == text.length() - 1 ? 0 : digits.getOrDefault(text.charAt(tenIndex + 1), 0);
            return tens * 10 + ones;
        }
        return digits.getOrDefault(text.charAt(0), 0);
    }

    private void validatePaperFile(MultipartFile file) {
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".pdf") || name.endsWith(".docx"))) {
            throw new IllegalArgumentException("论文文件仅支持 PDF 或 DOCX");
        }
        if (file.getSize() > config.getMaxPaperBytes()) {
            throw new IllegalArgumentException("论文文件超过 30MB 限制");
        }
    }

    private void validateFile(MultipartFile file, String suffix, long maxBytes, String label) {
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!name.endsWith(suffix)) {
            throw new IllegalArgumentException(label + "仅支持 " + suffix + " 格式");
        }
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException(label + "超过 30MB 限制");
        }
    }

    private void copyUpload(MultipartFile file, Path target) throws IOException {
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String extractJson(String response) {
        String text = response == null ? "" : response.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("mimo 未返回有效 JSON");
        }
        return text.substring(start, end + 1);
    }

    private JsonNode parseLlmJson(String response) throws IOException {
        String json = extractJson(response);
        try {
            return objectMapper.readTree(json);
        } catch (IOException firstError) {
            String repaired = repairSmartQuotedJson(json);
            if (!repaired.equals(json)) {
                try {
                    return objectMapper.readTree(repaired);
                } catch (IOException ignored) {
                    // Keep the original parser error; it points to the real malformed output.
                }
            }
            throw firstError;
        }
    }

    private boolean looksLikeTruncatedJson(String response, Exception error) {
        String message = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("unexpected end-of-input") || message.contains("expected close marker")) {
            return true;
        }
        String text = response == null ? "" : response.trim();
        if (text.isBlank()) return false;
        int openBraces = 0;
        int openBrackets = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (current == '{') openBraces++;
            if (current == '}') openBraces--;
            if (current == '[') openBrackets++;
            if (current == ']') openBrackets--;
        }
        return inString || openBraces > 0 || openBrackets > 0;
    }

    private String repairSmartQuotedJson(String json) {
        StringBuilder repaired = new StringBuilder(json.length());
        for (int index = 0; index < json.length(); index++) {
            char current = json.charAt(index);
            if (current == '“' || current == '”') {
                char previous = previousNonWhitespace(json, index);
                char next = nextNonWhitespace(json, index);
                if (previous == ':' || previous == '[' || previous == ',' || previous == '{'
                        || next == ':' || next == ',' || next == ']' || next == '}') {
                    repaired.append('"');
                } else {
                    repaired.append(current);
                }
            } else {
                repaired.append(current);
            }
        }
        return repaired.toString();
    }

    private char previousNonWhitespace(String value, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c)) return c;
        }
        return '\0';
    }

    private char nextNonWhitespace(String value, int index) {
        for (int i = index + 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c)) return c;
        }
        return '\0';
    }

    private boolean hasPaper(PptGenerationSession session) {
        return session.getPaperFileName() != null && Files.isRegularFile(session.getPaperPath());
    }

    private boolean hasTemplate(PptGenerationSession session) {
        return session.getTemplateFileName() != null && Files.isRegularFile(session.getTemplatePath());
    }

    private void sendSnapshot(PptGenerationSession session, SseEmitter emitter) {
        if ("completed".equals(session.getStatus())) {
            sendAndComplete(emitter, "done", Map.of("taskId", session.getTaskId()));
        } else if ("error".equals(session.getStatus())) {
            sendAndComplete(emitter, "task-error", Map.of("message",
                    session.getErrorMessage() == null ? "PPT 生成失败" : session.getErrorMessage()));
        } else if ("outline_ready".equals(session.getStatus())) {
            sendAndComplete(emitter, "outline-ready", Map.of("taskId", session.getTaskId(), "deck", getDeck(session.getTaskId())));
        } else if ("queued".equals(session.getStatus())) {
            send(emitter, "queued", Map.of("message", "任务正在等待后台生成", "queuePosition", session.getQueuePosition()));
        } else {
            send(emitter, "progress", Map.of(
                    "progress", session.getProgress(),
                    "stage", session.getProgressStage(),
                    "stageLabel", stageLabel(session.getProgressStage())));
        }
    }

    private void emit(PptGenerationSession session, String eventName, Object data) {
        List<SseEmitter> taskEmitters = emitters.get(session.getTaskId());
        if (taskEmitters == null) return;
        taskEmitters.forEach(emitter -> {
            if (!send(emitter, eventName, data)) removeEmitter(session.getTaskId(), emitter);
        });
    }

    private boolean send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    private void sendAndComplete(SseEmitter emitter, String eventName, Object data) {
        send(emitter, eventName, data);
        emitter.complete();
    }

    private void removeEmitter(String taskId, SseEmitter emitter) {
        List<SseEmitter> taskEmitters = emitters.get(taskId);
        if (taskEmitters == null) return;
        taskEmitters.remove(emitter);
        if (taskEmitters.isEmpty()) emitters.remove(taskId, taskEmitters);
    }

    private void completeEmitters(String taskId) {
        List<SseEmitter> taskEmitters = emitters.remove(taskId);
        if (taskEmitters != null) taskEmitters.forEach(SseEmitter::complete);
    }

    private void updateQueuePositions() {
        List<PptGenerationSession> queued = sessions.values().stream()
                .filter(session -> "queued".equals(session.getStatus()))
                .sorted(Comparator.comparingLong(PptGenerationSession::getCreatedAt))
                .toList();
        for (int index = 0; index < queued.size(); index++) {
            queued.get(index).setQueuePosition(index + 1);
        }
    }

    private void loadRecentSessions() {
        try (Stream<Path> taskDirs = Files.list(storageDir)) {
            taskDirs.filter(Files::isDirectory).forEach(taskDir -> {
                Path metadata = taskDir.resolve("task.json");
                if (!Files.isRegularFile(metadata)) return;
                try {
                    PptGenerationSession session = objectMapper.readValue(metadata.toFile(), PptGenerationSession.class);
                    session.setTaskDir(taskDir);
                    if (session.getAccessToken() == null || session.getAccessToken().isBlank()) {
                        session.setAccessToken(newAccessToken());
                        saveMetadata(session);
                    }
                    if (Set.of("queued", "generating", "outlining", "rendering", "revising").contains(session.getStatus())) {
                        session.setStatus("error");
                        session.setProgressStage("error");
                        session.setErrorMessage("后端重启后任务已中断，请重新提交");
                        saveMetadata(session);
                    }
                    sessions.put(session.getTaskId(), session);
                } catch (Exception e) {
                    log.warn("读取 PPT 生成任务记录失败: {}", metadata, e);
                }
            });
        } catch (IOException e) {
            log.warn("读取 PPT 生成任务目录失败: {}", storageDir, e);
        }
    }

    private void saveMetadata(PptGenerationSession session) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(session.getMetadataPath().toFile(), session);
        } catch (IOException e) {
            log.warn("保存 PPT 生成任务记录失败: taskId={}", session.getTaskId(), e);
        }
    }

    private void cleanupHistory() {
        int keep = Math.max(1, config.getMaxHistory());
        List<PptGenerationSession> terminal = sessions.values().stream()
                .filter(session -> Set.of("completed", "error", "outline_ready").contains(session.getStatus()))
                .sorted(Comparator.comparingLong(PptGenerationSession::getCreatedAt).reversed())
                .toList();
        for (int index = keep; index < terminal.size(); index++) {
            PptGenerationSession session = terminal.get(index);
            sessions.remove(session.getTaskId(), session);
            deleteRecursively(session.getTaskDir());
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException e) {
                    log.warn("清理 PPT 任务文件失败: {}", item, e);
                }
            });
        } catch (IOException e) {
            log.warn("清理 PPT 任务目录失败: {}", path, e);
        }
    }

    private PptGenerationSession requireSession(String taskId) {
        PptGenerationSession session = sessions.get(taskId);
        if (session == null) throw new IllegalArgumentException("任务不存在");
        return session;
    }

    private PptGenerationSession requireAuthorizedSession(String taskId, String accessToken) {
        PptGenerationSession session = requireSession(taskId);
        if (!tokenMatches(session.getAccessToken(), accessToken)) {
            throw new IllegalArgumentException("任务不存在");
        }
        return session;
    }

    private boolean tokenMatches(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.trim().getBytes(StandardCharsets.UTF_8));
    }

    private Set<String> parseAccessTokens(String accessTokens) {
        if (accessTokens == null || accessTokens.isBlank()) return Set.of();
        Set<String> tokens = new HashSet<>();
        for (String token : accessTokens.split(",")) {
            String value = token.trim();
            if (!value.isBlank() && value.length() <= 128) tokens.add(value);
        }
        return tokens;
    }

    private String newAccessToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void ensureTaskIdle(PptGenerationSession session) {
        if (Set.of("queued", "outlining", "rendering", "revising").contains(session.getStatus())) {
            throw new IllegalStateException("任务正在处理中，请等待当前操作完成");
        }
    }

    private byte[] readOptionalBytes(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
        } catch (IOException e) {
            throw new IllegalStateException("读取现有 PPT 大纲失败: " + e.getMessage(), e);
        }
    }

    private void restoreDeck(Path path, byte[] bytes) {
        try {
            if (bytes == null) Files.deleteIfExists(path);
            else Files.write(path, bytes);
        } catch (IOException e) {
            log.error("恢复 PPT 大纲失败: {}", path, e);
        }
    }

    private void restoreSessionState(PptGenerationSession session, String status, String stage, String error,
                                     double progress, String templateMode, int extractionPercent) {
        session.setStatus(status);
        session.setProgressStage(stage);
        session.setErrorMessage(error);
        session.setProgress(progress);
        session.setTemplateMode(templateMode);
        session.setExtractionPercent(extractionPercent);
    }

    private void restoreRevisionState(PptGenerationSession session, String status, String stage, double progress) {
        synchronized (session) {
            if ("revising".equals(session.getStatus())) {
                session.setStatus(status);
                session.setProgressStage(stage);
                session.setProgress(progress);
                saveMetadata(session);
            }
        }
    }

    private String trimLog(String output) {
        if (output == null) return "";
        int max = 2000;
        return output.length() <= max ? output : output.substring(output.length() - max);
    }

    public String stageLabel(String stage) {
        return switch (stage == null ? "" : stage) {
            case "queued" -> "等待后台生成";
            case "extracting" -> "读取论文和模板";
            case "refreshing_assets" -> "刷新论文和模板素材";
            case "reusing_assets" -> "复用已抽取素材";
            case "planning" -> "mimo 规划 PPT 结构";
            case "outline_ready" -> "大纲待确认";
            case "revising" -> "修改 PPT 大纲";
            case "rendering" -> "生成可编辑 PPTX";
            case "completed" -> "PPT 生成完成";
            case "error" -> "PPT 生成失败";
            default -> "处理中";
        };
    }
}
