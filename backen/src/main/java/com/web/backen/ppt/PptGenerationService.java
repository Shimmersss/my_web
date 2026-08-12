package com.web.backen.ppt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.auth.RuntimeConfigService;
import com.web.backen.config.PptGenerationConfig;
import com.web.backen.translate.LlmService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Durable orchestration boundary for presentation tasks.
 *
 * <p>Authentication, quota, queueing, recovery, storage and SSE stay in Spring.
 * Research, planning, authoring, rendering and visual repair are exclusively owned
 * by {@link PptAgentRunner}; no deck-JSON or fixed-renderer fallback lives here.</p>
 */
@Service
public class PptGenerationService {
    private static final Logger log = LoggerFactory.getLogger(PptGenerationService.class);
    private static final String AUTO_PROMPT =
            "请根据上传的资料自动提炼重点，判断合适的受众和叙事方式，生成一份结构清晰、视觉专业、可编辑的演示文稿。"
                    + "保留关键事实、数据和结论，必要时补充目录、图表解读、行动建议或下一步。";
    private static final Set<String> SOURCE_ARCHIVES = Set.of(".docx", ".pptx", ".xlsx");
    private static final Set<String> SOURCE_SUFFIXES =
            Set.of(".pdf", ".docx", ".pptx", ".xlsx", ".txt", ".md", ".csv", ".html", ".htm");

    private final PptGenerationConfig config;
    private final PptInputExtractor inputExtractor;
    private final ObjectMapper objectMapper;
    private final QuotaService quotaService;
    private final PptAgentRunner agentRunner;
    private final RuntimeConfigService runtimeConfig;
    private final ConcurrentHashMap<String, PptGenerationSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotencyClaims = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final PptxQualityGate qualityGate = new PptxQualityGate();
    private final ThreadPoolExecutor executor;
    private Path storageDir;

    public PptGenerationService(PptGenerationConfig config, PptInputExtractor inputExtractor,
                                LlmService ignored, ObjectMapper objectMapper) {
        this(config, inputExtractor, ignored, objectMapper, null, null, null);
    }

    public PptGenerationService(PptGenerationConfig config, PptInputExtractor inputExtractor,
                                LlmService ignored, ObjectMapper objectMapper, QuotaService quotaService) {
        this(config, inputExtractor, ignored, objectMapper, quotaService, null, null);
    }

    public PptGenerationService(PptGenerationConfig config, PptInputExtractor inputExtractor,
                                LlmService ignored, ObjectMapper objectMapper, QuotaService quotaService,
                                PptAgentRunner agentRunner) {
        this(config, inputExtractor, ignored, objectMapper, quotaService, agentRunner, null);
    }

    @Autowired
    public PptGenerationService(PptGenerationConfig config, PptInputExtractor inputExtractor,
                                LlmService ignored, ObjectMapper objectMapper, QuotaService quotaService,
                                PptAgentRunner agentRunner, RuntimeConfigService runtimeConfig) {
        this.config = config;
        this.inputExtractor = inputExtractor;
        this.objectMapper = objectMapper;
        this.quotaService = quotaService;
        this.agentRunner = agentRunner;
        this.runtimeConfig = runtimeConfig;
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
        reconcilePendingRefunds();
        recoverPendingSessions();
        cleanupHistory();
        log.info("PPT Agent 队列已启动: workers=1, queueCapacity={}, maxPerUserHistory={}, maxTotalHistory={}, storage={}",
                config.getQueueCapacity(), maxPerUserHistory(), maxTotalHistory(), storageDir);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        inputExtractor.shutdown();
    }

    public List<Map<String, Object>> templates() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> designs = List.of(
                "blue-line-courseware", "color-stripes-documentary", "cream-collage", "dark-themed-data",
                "dusk-violet-consulting", "fresh-brand", "gold-orange-type-journal", "honey-orange-memo",
                "indigo-due-diligence", "ink-green-market-trends", "lead-gray-quarterly", "light-blue-product",
                "lime-coral-workshop", "minimal-red-academic", "mint-green-training", "navy-cyan-technology",
                "navy-gold-corporate", "neon-cyberpunk", "orange-black-launch", "paper-blue-research",
                "peach-rose-story", "purple-gradient-ai", "red-black-editorial", "sand-brown-heritage",
                "sky-blue-education", "slate-teal-analytics", "soft-pink-brand", "teal-grid-engineering",
                "warm-yellow-business", "white-blue-medical");
        List<List<String>> palettes = List.of(
                List.of("2563EB", "EFF6FF", "F59E0B", "FFFFFF", "0F172A"),
                List.of("0F766E", "ECFDF5", "FB7185", "FFFFFF", "134E4A"),
                List.of("7C3AED", "F5F3FF", "22D3EE", "FFFFFF", "1E1B4B"),
                List.of("EA580C", "FFF7ED", "111827", "FFFFFF", "431407"),
                List.of("DC2626", "FEF2F2", "F59E0B", "FFFFFF", "450A0A"));
        for (int index = 0; index < designs.size(); index++) {
            String design = designs.get(index);
            result.add(pptxTemplate("pptd-" + design, titleCase(design),
                    "open-kimi-ppt Skill 设计系统；由 Codex 根据内容选择版式并生成可编辑 PPTD",
                    palettes.get(index % palettes.size()), design));
        }
        result.addAll(List.of(
                htmlTemplate("html-reveal-black", "Reveal Black", "纯黑演讲主题", List.of("111111", "000000", "D9A441", "111111", "F8FAFC"), "reveal-black"),
                htmlTemplate("html-reveal-white", "Reveal White", "白底高可读主题", List.of("1D4ED8", "FFFFFF", "D97706", "F8FAFC", "1F2937"), "reveal-white"),
                htmlTemplate("html-reveal-beige", "Reveal Beige", "暖米色纸张感", List.of("8C3B1F", "F7F1E3", "B7791F", "FFFDF7", "3D2B1F"), "reveal-beige"),
                htmlTemplate("html-reveal-sky", "Reveal Sky", "蓝色渐变舞台感", List.of("0EA5E9", "075985", "FDE047", "E0F2FE", "0F172A"), "reveal-sky"),
                htmlTemplate("html-reveal-league", "Reveal League", "高对比杂志式主题", List.of("E11D48", "1E293B", "FACC15", "0F172A", "F8FAFC"), "reveal-league"),
                htmlTemplate("html-reveal-night", "Reveal Night", "夜间技术舞台主题", List.of("60A5FA", "0B1120", "A78BFA", "111827", "E2E8F0"), "reveal-night"),
                htmlTemplate("html-reveal-solarized", "Reveal Solarized", "柔和护眼的数据主题", List.of("268BD2", "002B36", "B58900", "FDF6E3", "586E75"), "reveal-solarized"),
                htmlTemplate("html-reveal-gradient", "Reveal Gradient", "渐变卡片商业主题", List.of("8B5CF6", "312E81", "22D3EE", "F5F3FF", "1F2937"), "reveal-gradient"),
                htmlTemplate("html-editorial-ink", "Editorial Ink", "纸刊留白、衬线标题与编辑部式红色批注，适合洞察和品牌故事",
                        List.of("D9482B", "F5F1E8", "A16207", "DED6C8", "171717"), "editorial-ink"),
                htmlTemplate("html-neon-grid", "Neon Grid", "深色网格与青色霓虹界面，适合 AI、数据产品和技术发布",
                        List.of("2DD4BF", "070A13", "8B5CF6", "172033", "ECFEFF"), "neon-grid"),
                htmlTemplate("html-terminal-green", "Terminal Green", "终端式等宽字体与命令行节奏，适合开发者、架构和开源项目",
                        List.of("4ADE80", "07120D", "FACC15", "10261A", "D1FAE5"), "terminal-green"),
                htmlTemplate("html-gallery-cream", "Gallery Cream", "画廊米白、酒红强调与高雅衬线排版，适合文化、设计和高端品牌",
                        List.of("9F1239", "F4EFE5", "C08457", "E7DAC9", "3B2524"), "gallery-cream"),
                htmlTemplate("html-roman-forum", "Roman Forum", "古罗马石刻、赤陶红与柱式秩序，适合历史、文化、制度和经典叙事",
                        List.of("A4432F", "F2EAD8", "B99352", "D8C6A6", "2F2923"), "roman-forum")
        ));
        return List.copyOf(result);
    }

    private String titleCase(String design) {
        return Stream.of(design.split("-"))
                .map(part -> part.isBlank() ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private Map<String, Object> pptxTemplate(String key, String name, String description,
                                             List<String> palette, String design) {
        return template(key, name, description, palette, design, "PPTD 设计系统", List.of("pptx"),
                "open-kimi-ppt-skill 1.3.0", "MIT + separately authorized editor assets",
                "https://github.com/Binaryify/open-kimi-ppt-skill",
                "PPTX 权限由后台“PPT 生成”节目范围控制；自定义 PPTX 模板会作为视觉参考传给隔离 Codex 工作区");
    }

    private Map<String, Object> htmlTemplate(String key, String name, String description,
                                             List<String> palette, String design) {
        return template(key, name, description, palette, design, "HTML 交互主题", List.of("html"),
                "reveal.js theme", "MIT", "https://github.com/hakimel/reveal.js",
                "基于 reveal.js 运行时，由 HTML 演示 Skill 自主编排");
    }

    private Map<String, Object> template(String key, String name, String description, List<String> palette,
                                         String design, String categoryLabel, List<String> formats,
                                         String source, String license, String sourceUrl, String usageNote) {
        return Map.ofEntries(
                Map.entry("key", key), Map.entry("name", name), Map.entry("description", description),
                Map.entry("palette", palette), Map.entry("source", source), Map.entry("license", license),
                Map.entry("sourceUrl", sourceUrl), Map.entry("design", design),
                Map.entry("category", formats.contains("html") ? "html" : "pptd"),
                Map.entry("categoryLabel", categoryLabel), Map.entry("formats", formats),
                Map.entry("complexity", "rich"), Map.entry("supports", formats.contains("html")
                        ? List.of("semantic-layouts", "image-hero", "stats", "process", "comparison", "timeline", "fragments", "auto-animate", "reduced-motion")
                        : List.of("text", "image", "metrics", "charts")),
                Map.entry("recommendedFor", List.of("研究汇报", "商业演示", "课程展示")),
                Map.entry("motionModes", formats.contains("html")
                        ? List.of("auto", "subtle", "expressive", "off") : List.of()),
                Map.entry("openSource", true), Map.entry("usageNote", usageNote));
    }

    public PptGenerationSession createTask(String prompt, String templateKey, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile sourceFile) throws IOException {
        return createTask(prompt, templateKey, extractionPercent, templateFile, sourceFile, null);
    }

    public PptGenerationSession createTask(String prompt, String templateKey, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile sourceFile, AuthUser user) throws IOException {
        return createTask(prompt, templateKey, extractionPercent, templateFile, sourceFile, user, null);
    }

    public PptGenerationSession createTask(String prompt, String templateKey, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile sourceFile, AuthUser user,
                                           String clientRequestId) throws IOException {
        return createTask(prompt, templateKey, extractionPercent, templateFile, sourceFile, user, clientRequestId, "pptx");
    }

    public PptGenerationSession createTask(String prompt, String templateKey, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile sourceFile, AuthUser user,
                                           String clientRequestId, String outputFormat) throws IOException {
        return createTask(prompt, templateKey, extractionPercent, templateFile, sourceFile, user,
                clientRequestId, outputFormat, "auto");
    }

    public PptGenerationSession createTask(String prompt, String templateKey, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile sourceFile, AuthUser user,
                                           String clientRequestId, String outputFormat, String researchMode) throws IOException {
        return createTask(prompt, templateKey, extractionPercent, templateFile, sourceFile, user,
                clientRequestId, outputFormat, researchMode, "best_effort", "Microsoft YaHei");
    }

    public PptGenerationSession createTask(String prompt, String templateKey, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile sourceFile, AuthUser user,
                                           String clientRequestId, String outputFormat, String researchMode,
                                           String fontFamily) throws IOException {
        return createTask(prompt, templateKey, extractionPercent, templateFile, sourceFile, user,
                clientRequestId, outputFormat, researchMode, "best_effort", fontFamily);
    }

    public PptGenerationSession createTask(String prompt, String templateKey, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile sourceFile, AuthUser user,
                                           String clientRequestId, String outputFormat, String researchMode,
                                           String visualMode, String fontFamily) throws IOException {
        return createTask(prompt, templateKey, extractionPercent, templateFile, sourceFile, user,
                clientRequestId, outputFormat, researchMode, visualMode, fontFamily, "off");
    }

    public PptGenerationSession createTask(String prompt, String templateKey, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile sourceFile, AuthUser user,
                                           String clientRequestId, String outputFormat, String researchMode,
                                           String visualMode, String fontFamily, String imageGenerationMode) throws IOException {
        return createTask(prompt, templateKey, extractionPercent, templateFile, sourceFile, user,
                clientRequestId, outputFormat, researchMode, visualMode, fontFamily, imageGenerationMode, "auto");
    }

    public PptGenerationSession createTask(String prompt, String templateKey, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile sourceFile, AuthUser user,
                                           String clientRequestId, String outputFormat, String researchMode,
                                           String visualMode, String fontFamily, String imageGenerationMode,
                                           String motionMode) throws IOException {
        return createTask(prompt, templateKey, extractionPercent, templateFile, sourceFile, user,
                clientRequestId, outputFormat, researchMode, visualMode, fontFamily, imageGenerationMode,
                motionMode, 0, 0);
    }

    public PptGenerationSession createTask(String prompt, String templateKey, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile sourceFile, AuthUser user,
                                           String clientRequestId, String outputFormat, String researchMode,
                                           String visualMode, String fontFamily, String imageGenerationMode,
                                           String motionMode, Integer requestedPageCount,
                                           Integer requestedImageGenerationCount) throws IOException {
        assertDeploymentNotLocked();
        String cleanPrompt = validatePrompt(prompt);
        String normalizedOutputFormat = normalizeOutputFormat(outputFormat);
        if ("html".equals(normalizedOutputFormat) && templateFile != null && !templateFile.isEmpty()) {
            throw new IllegalArgumentException("HTML 输出不接受 PPTX 模板，请选择 HTML 主题");
        }
        if (cleanPrompt.isBlank() && (sourceFile == null || sourceFile.isEmpty())) {
            throw new IllegalArgumentException("请输入提示词，或上传一份资料");
        }
        if (cleanPrompt.isBlank()) cleanPrompt = AUTO_PROMPT;
        String requestId = normalizeClientRequestId(clientRequestId);
        String claimKey = claimIdempotency(user, requestId);
        if (claimKey != null) {
            PptGenerationSession existing = claimedSession(claimKey, user.id(), requestId);
            if (existing != null) return existing;
            if (idempotencyClaims.putIfAbsent(claimKey, "__creating__") != null) {
                throw new IllegalStateException("任务正在创建，请稍后再试");
            }
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        Path taskDir = Files.createDirectories(storageDir.resolve(taskId));
        PptGenerationSession session = new PptGenerationSession(taskId, cleanPrompt, taskDir);
        if (user != null) session.setUserId(user.id());
        session.setClientRequestId(requestId.isBlank() ? null : requestId);
        session.setAccessToken(newAccessToken());
        session.setOutputFormat(normalizedOutputFormat);
        session.setEngine("html".equals(normalizedOutputFormat) ? "codex-html" : "codex-pptd");
        session.setTemplateKey(normalizeTemplateKey(templateKey, normalizedOutputFormat));
        session.setResearchMode(normalizeResearchMode(researchMode));
        session.setVisualMode(normalizeVisualMode(visualMode));
        session.setMotionMode(normalizeMotionMode(motionMode, normalizedOutputFormat));
        session.setImageGenerationMode(normalizeImageGenerationMode(imageGenerationMode, normalizedOutputFormat));
        session.setRequestedPageCount(normalizeRequestedPageCount(requestedPageCount));
        session.setRequestedImageGenerationCount(normalizeRequestedImageGenerationCount(
                requestedImageGenerationCount, session.getImageGenerationMode(), normalizedOutputFormat));
        session.setFontFamily(normalizeFontFamily(fontFamily));
        session.setQuotaRequired(user != null && quotaService != null && !user.isRoot());
        session.setExtractionPercent(100);
        session.setStatus("creating");
        session.setProgressStage("creating");
        sessions.put(session.getTaskId(), session);
        saveMetadata(session);

        try {
            acceptUploads(session, templateFile, sourceFile);
            session.setCreationReady(true);
            saveMetadata(session);
            charge(session, user, "PPT", "PPT 生成任务");
            queue(session, claimKey, "任务已进入 PPT Agent 队列");
            return session;
        } catch (RejectedExecutionException e) {
            handleCreationFailure(session, claimKey, e, "PPT 队列已满自动退回额度");
            throw new IllegalStateException("PPT 生成队列已满，请稍后再试");
        } catch (Exception e) {
            handleCreationFailure(session, claimKey, e, "PPT 任务创建失败自动退回额度");
            if (e instanceof IOException io) throw io;
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public PptGenerationSession createRevisionTask(PptGenerationSession original, String revisionPrompt,
                                                   List<Map<String, Object>> ignoredSlideEdits, AuthUser user,
                                                   String clientRequestId) throws IOException {
        assertDeploymentNotLocked();
        if (original == null || !"completed".equals(original.getStatus())) {
            throw new IllegalArgumentException("只有已生成完成的演示才可修改");
        }
        if (user == null || (!user.isRoot() && original.getUserId() != user.id())) {
            throw new IllegalArgumentException("无权修改该 PPT 任务");
        }
        String instruction = validateRevisionPrompt(revisionPrompt);
        if (instruction.isBlank()) throw new IllegalArgumentException("请输入二次修改要求");
        String requestId = normalizeClientRequestId(clientRequestId);
        String claimKey = claimIdempotency(user, requestId);
        if (claimKey != null) {
            PptGenerationSession existing = claimedSession(claimKey, user.id(), requestId);
            if (existing != null) return existing;
            if (idempotencyClaims.putIfAbsent(claimKey, "__creating__") != null) {
                throw new IllegalStateException("任务正在创建，请稍后再试");
            }
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        Path taskDir = Files.createDirectories(storageDir.resolve(taskId));
        String combined = validatePrompt(trim(original.getPrompt(), 4200)
                + "\n\n【二次修改要求】\n" + trim(instruction, 3600));
        PptGenerationSession session = new PptGenerationSession(taskId, combined, taskDir);
        session.setUserId(user.id());
        session.setClientRequestId(requestId.isBlank() ? null : requestId);
        session.setAccessToken(newAccessToken());
        session.setTemplateKey(original.getTemplateKey());
        session.setOutputFormat(original.getOutputFormat());
        session.setEngine(original.getEngine());
        session.setVersion(original.getVersion() + 1);
        session.setParentTaskId(original.getTaskId());
        session.setResearchMode(original.getResearchMode());
        session.setVisualMode(original.getVisualMode());
        session.setMotionMode(original.getMotionMode());
        session.setImageGenerationMode(original.getImageGenerationMode());
        session.setRequestedPageCount(original.getRequestedPageCount());
        session.setRequestedImageGenerationCount(original.getRequestedImageGenerationCount());
        session.setFontFamily(original.getFontFamily());
        session.setTemplateFileName(original.getTemplateFileName());
        session.setPaperFileName(original.getPaperFileName());
        session.setRevisionOfTaskId(original.getTaskId());
        session.setRevisionPrompt(instruction);
        session.setExtractionPercent(100);
        session.setQuotaRequired(quotaService != null && !user.isRoot());
        session.setStatus("creating");
        session.setProgressStage("creating");
        sessions.put(session.getTaskId(), session);
        saveMetadata(session);

        try {
            copyOptional(original.getTemplatePath(), session.getTemplatePath());
            copyOptional(original.getPaperPath(), session.getPaperPath());
            copyDirectoryContents(original.getImagesDir(), session.getImagesDir());
            copyDirectoryContents(original.getTaskDir().resolve("web-images"), taskDir.resolve("web-images"));
            copyOptional(original.getAgentPlanPath(), taskDir.resolve("previous-agent-plan.json"));
            copyOptional(original.getSourcesPath(), taskDir.resolve("previous-sources.json"));
            copyOptional(original.getOutputPath(), taskDir.resolve(
                    "html".equalsIgnoreCase(original.getOutputFormat()) ? "previous-output.html" : "previous-output.pptx"));
            copyDirectoryContents(original.getPreviewDir(), taskDir.resolve("previous-preview"));
            copyDirectoryContents(original.getPptdProjectDir(), taskDir.resolve("previous-pptd-project"));
            session.setCreationReady(true);
            saveMetadata(session);
            charge(session, user, "PPT_REVISION", "PPT 二次修改");
            queue(session, claimKey, "二次修改任务已进入 PPT Agent 队列");
            return session;
        } catch (RejectedExecutionException e) {
            handleCreationFailure(session, claimKey, e, "PPT 二次修改队列已满自动退回额度");
            throw new IllegalStateException("PPT 生成队列已满，请稍后再试");
        } catch (Exception e) {
            handleCreationFailure(session, claimKey, e, "PPT 二次修改创建失败自动退回额度");
            if (e instanceof IOException io) throw io;
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void acceptUploads(PptGenerationSession session, MultipartFile templateFile,
                               MultipartFile sourceFile) throws IOException {
        if (templateFile != null && !templateFile.isEmpty()) {
            validateFile(templateFile, ".pptx", config.getMaxTemplateBytes(), "PPT 模板");
            session.setTemplateFileName(templateFile.getOriginalFilename());
            copyUpload(templateFile, session.getTemplatePath());
            validateArchive(session.getTemplatePath());
        }
        if (sourceFile != null && !sourceFile.isEmpty()) {
            validateSourceFile(sourceFile);
            session.setPaperFileName(sourceFile.getOriginalFilename());
            copyUpload(sourceFile, session.getPaperPath());
            if (SOURCE_ARCHIVES.stream().anyMatch(session.getPaperFileName().toLowerCase(Locale.ROOT)::endsWith)) {
                validateArchive(session.getPaperPath());
            }
        }
    }

    private void validateArchive(Path path) throws IOException {
        PptArchiveGuard.validate(path, config.getMaxArchiveEntries(), config.getMaxArchiveUncompressedBytes(),
                config.getMaxArchiveEntryBytes(), config.getMaxArchiveCompressionRatio());
    }

    private void charge(PptGenerationSession session, AuthUser user, String type, String description) {
        int imageCount = requestedImageGenerationCount(session);
        int imageCost = quotaService == null || runtimeConfig == null ? 0
                : imageCount * quotaService.imageCredit(runtimeConfig.imageGenerationQuality());
        session.setImageGenerationCount(imageCount);
        session.setImageGenerationCreditCost(imageCost);
        if (user == null || quotaService == null || user.isRoot()) {
            saveMetadata(session);
            return;
        }
        int pptCost = quotaService.pptCreditPerTask();
        int cost = pptCost + imageCost;
        String billedDescription = imageCount == 0 ? description
                : description + "（含 GPT Image 2 " + imageCount + " 张，"
                + runtimeConfig.imageGenerationQuality() + "）";
        long transaction = quotaService.spend(user.id(), cost, type, session.getTaskId(), billedDescription);
        session.setCreditCost(cost);
        session.setCreditTransactionId(transaction);
        session.setCreditRefunded(false);
        saveMetadata(session);
    }

    private int requestedImageGenerationCount(PptGenerationSession session) {
        if (runtimeConfig == null || session == null || !"pptx".equals(session.getOutputFormat())) return 0;
        return PptImageGenerationService.requestedImageCount(session.getImageGenerationMode(),
                runtimeConfig.imageGenerationMaxImages(), session.getRequestedImageGenerationCount());
    }

    private void queue(PptGenerationSession session, String claimKey, String message) {
        session.setOutputFileName(outputFileName(session));
        session.setStatus("queued");
        session.setProgressStage("queued");
        sessions.put(session.getTaskId(), session);
        if (claimKey != null) idempotencyClaims.put(claimKey, session.getTaskId());
        saveMetadata(session);
        executor.execute(() -> runGenerationTask(session));
        updateQueuePositions();
        emit(session, "queued", Map.of("message", message, "queuePosition", session.getQueuePosition()));
    }

    private void runGenerationTask(PptGenerationSession session) {
        session.setStatus("generating");
        session.setQueuePosition(0);
        saveAndProgress(session, 5, "planning", "正在启动演示 Agent");
        updateQueuePositions();
        try {
            if (agentRunner == null) throw new IllegalStateException("PPT Agent runner 未配置");
            Files.createDirectories(session.getImagesDir());
            String sourceText = inputExtractor.extractPaperText(
                    hasPaper(session) ? session.getPaperPath() : null,
                    session.getPaperFileName(), session.getImagesDir(), 100,
                    Math.min(config.getMaxExtractedImages(), 12), 0);
            Files.writeString(session.getTaskDir().resolve("source.txt"),
                    sourceText == null ? "" : sourceText, StandardCharsets.UTF_8);
            agentRunner.run(session, storageDir, (event, data) -> handleAgentEvent(session, event, data));
            verifyAgentArtifacts(session);
            session.setStatus("completed");
            session.setProgress(100);
            session.setProgressStage("completed");
            session.setCompletedAt(System.currentTimeMillis());
            saveMetadata(session);
            emit(session, "done", Map.of("taskId", session.getTaskId(), "qaValid", session.isQaValid(),
                    "sourceCount", session.getSourceCount()));
            completeEmitters(session.getTaskId());
        } catch (Exception e) {
            failTask(session, e);
        } finally {
            cleanupHistory();
        }
    }

    private void verifyAgentArtifacts(PptGenerationSession session) throws IOException {
        if (!Files.isRegularFile(session.getOutputPath()) || Files.size(session.getOutputPath()) < 256) {
            throw new IllegalStateException("Agent 未生成有效演示文件");
        }
        if (!Files.isRegularFile(session.getPreviewPath()) || !Files.isDirectory(session.getPreviewDir())) {
            throw new IllegalStateException("Agent 未生成真实逐页预览");
        }
        int removedParts = 0;
        if ("pptx".equalsIgnoreCase(session.getOutputFormat())) {
            JsonNode plan = objectMapper.readTree(session.getAgentPlanPath().toFile());
            Integer expected = plan.path("slides").isArray() ? plan.path("slides").size() : null;
            removedParts = PptxPackageCleaner.clean(session.getPptxOutputPath());
            qualityGate.validate(session.getPptxOutputPath(), expected);
        }
        Path qaPath = session.getTaskDir().resolve("quality-report.json");
        JsonNode qa = Files.isRegularFile(qaPath)
                ? objectMapper.readTree(qaPath.toFile()) : objectMapper.createObjectNode().put("valid", false);
        if (!(qa instanceof ObjectNode objectQa)) throw new IllegalStateException("Agent QA 报告结构无效");
        objectQa.putObject("package").put("valid", true)
                .put("orphanedPartsRemoved", removedParts).put("checkedAt", System.currentTimeMillis());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(qaPath.toFile(), qa);
        Map<String, Object> preview = objectMapper.readValue(session.getPreviewPath().toFile(), Map.class);
        preview.put("qa", objectMapper.convertValue(qa, Map.class));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(session.getPreviewPath().toFile(), preview);
        session.setQaValid(qa.path("valid").asBoolean(false));
        // Visual QA is delivery guidance, not an availability gate. The output
        // has already passed real rendering and, for PPTX, package integrity
        // validation above; keep the report so the UI can show its warnings.
    }

    private void handleAgentEvent(PptGenerationSession session, String eventName, Map<String, Object> data) {
        if (data.get("progress") instanceof Number value) session.setProgress(value.doubleValue());
        if (data.get("sourceCount") instanceof Number value) session.setSourceCount(value.intValue());
        if (data.get("iteration") instanceof Number value) session.setAgentIteration(value.intValue());
        String stage = switch (eventName) {
            case "researching", "planning", "authoring", "rendering", "reviewing", "revising" -> eventName;
            case "task-error" -> "error";
            default -> session.getProgressStage();
        };
        session.setProgressStage(stage);
        saveMetadata(session);
        if (!Set.of("done", "task-error").contains(eventName)) {
            Map<String, Object> payload = new LinkedHashMap<>(data);
            payload.put("stage", stage);
            payload.putIfAbsent("stageLabel", payload.getOrDefault("message", stageLabel(stage)));
            emit(session, eventName, payload);
            emit(session, "progress", payload);
        }
    }

    public Map<String, Object> preview(PptGenerationSession session) throws IOException {
        if (session == null || !"completed".equals(session.getStatus())) {
            throw new IllegalArgumentException("PPT 尚未生成完成，暂时无法预览");
        }
        if (!Files.isRegularFile(session.getPreviewPath())) throw new IllegalArgumentException("真实预览尚未生成");
        return objectMapper.readValue(session.getPreviewPath().toFile(), Map.class);
    }

    public Map<String, Object> pptdProject(PptGenerationSession session) throws IOException {
        requireCompletedPptd(session);
        Path root = session.getPptdProjectDir().toAbsolutePath().normalize();
        List<Path> manifests;
        try (Stream<Path> stream = Files.list(root)) {
            manifests = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".pptd")).toList();
        }
        if (manifests.size() != 1) throw new IllegalStateException("PPTD 项目清单无效");
        Map<String, String> textFiles = new LinkedHashMap<>();
        textFiles.put(manifests.get(0).getFileName().toString(), Files.readString(manifests.get(0), StandardCharsets.UTF_8));
        Path pages = root.resolve("pages");
        if (Files.isDirectory(pages)) {
            try (Stream<Path> stream = Files.list(pages)) {
                for (Path page : stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".page")).sorted().toList()) {
                    if (Files.size(page) > 2L * 1024 * 1024) throw new IllegalStateException("PPTD 页面超过读取上限");
                    textFiles.put("pages/" + page.getFileName(), Files.readString(page, StandardCharsets.UTF_8));
                }
            }
        }
        List<Map<String, Object>> media = new ArrayList<>();
        Path mediaRoot = root.resolve("media");
        if (Files.isDirectory(mediaRoot)) {
            try (Stream<Path> stream = Files.walk(mediaRoot)) {
                for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    media.add(Map.of("path", relative, "size", Files.size(file),
                            "contentType", Files.probeContentType(file) == null ? "application/octet-stream" : Files.probeContentType(file)));
                }
            }
        }
        return Map.of("taskId", session.getTaskId(), "version", session.getVersion(),
                "parentTaskId", session.getParentTaskId() == null ? "" : session.getParentTaskId(),
                "files", textFiles, "media", media);
    }

    public Path pptdProjectFile(PptGenerationSession session, String relativePath) {
        requireCompletedPptd(session);
        String clean = relativePath == null ? "" : relativePath.replace('\\', '/');
        if (clean.isBlank() || clean.startsWith("/") || clean.contains("../") || clean.contains("/..")
                || !(clean.endsWith(".png") || clean.endsWith(".jpg") || clean.endsWith(".jpeg")
                || clean.endsWith(".gif") || clean.endsWith(".svg") || clean.endsWith(".webp"))) {
            throw new IllegalArgumentException("PPTD 媒体路径无效");
        }
        Path root = session.getPptdProjectDir().toAbsolutePath().normalize();
        Path file = root.resolve(clean).normalize();
        if (!file.startsWith(root.resolve("media")) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("PPTD 媒体不存在");
        }
        return file;
    }

    public Path artifact(PptGenerationSession session, String artifact) {
        if (session == null || !"completed".equals(session.getStatus())) throw new IllegalStateException("PPT 尚未生成完成");
        if ("pptd".equalsIgnoreCase(artifact)) {
            if (!Files.isRegularFile(session.getPptdZipPath())) throw new IllegalArgumentException("PPTD 项目包不存在");
            return session.getPptdZipPath();
        }
        return getOutput(session);
    }

    public synchronized PptGenerationSession createManualVersion(PptGenerationSession parent, int baseVersion,
                                                     List<Map<String, Object>> changes, AuthUser user) throws IOException {
        assertDeploymentNotLocked();
        requireCompletedPptd(parent);
        if (user == null || (!user.isRoot() && parent.getUserId() != user.id())) {
            throw new IllegalArgumentException("仅任务所有者可保存 PPTD 编辑版本");
        }
        if (baseVersion != parent.getVersion()) throw new IllegalStateException("版本冲突：请重新加载最新版本");
        if (sessions.values().stream().anyMatch(session -> parent.getTaskId().equals(session.getParentTaskId())
                && session.getVersion() > baseVersion)) {
            throw new IllegalStateException("版本冲突：此版本已有更新，请重新加载最新版本");
        }
        if (changes == null || changes.isEmpty() || changes.size() > 60) throw new IllegalArgumentException("changes 数量必须为 1-60");
        List<Map<String, Object>> normalizedChanges = new ArrayList<>();
        long bytes = 0;
        Path parentProject = parent.getPptdProjectDir().toAbsolutePath().normalize();
        for (Map<String, Object> change : changes) {
            String relative = String.valueOf(change.getOrDefault("path", "")).replace('\\', '/').trim();
            String content = String.valueOf(change.getOrDefault("content", ""));
            bytes += content.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 8L * 1024 * 1024) throw new IllegalArgumentException("编辑内容总量超过 8MB");
            if (relative.isBlank() || relative.startsWith("/") || relative.contains("..")
                    || !(relative.endsWith(".pptd") || relative.startsWith("pages/") && relative.endsWith(".page"))) {
                throw new IllegalArgumentException("只允许修改根 .pptd 或 pages/*.page");
            }
            Path source = parentProject.resolve(relative).normalize();
            if (!source.startsWith(parentProject) || !Files.isRegularFile(source)) {
                throw new IllegalArgumentException("编辑文件不存在或路径越界");
            }
            normalizedChanges.add(Map.of("path", relative, "content", content));
        }
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        Path taskDir = Files.createDirectories(storageDir.resolve(taskId));
        PptGenerationSession session = new PptGenerationSession(taskId, parent.getPrompt(), taskDir);
        session.setUserId(user.id());
        session.setAccessToken(newAccessToken());
        session.setOutputFormat("pptx");
        session.setEngine("codex-pptd");
        session.setTemplateKey(parent.getTemplateKey());
        session.setFontFamily(parent.getFontFamily());
        session.setVersion(parent.getVersion() + 1);
        session.setParentTaskId(parent.getTaskId());
        session.setRevisionOfTaskId(parent.getTaskId());
        session.setRevisionPrompt("PPTD 编辑器手工保存");
        session.setOutputFileName(outputFileName(session));
        session.setStatus("creating");
        session.setProgressStage("creating");
        sessions.put(taskId, session);
        copyDirectoryContents(parent.getPptdProjectDir(), session.getPptdProjectDir());
        copyDirectoryContents(parent.getTaskDir().resolve("web-images"), taskDir.resolve("web-images"));
        for (Map<String, Object> change : normalizedChanges) {
            String relative = String.valueOf(change.get("path"));
            byte[] encoded = String.valueOf(change.get("content")).getBytes(StandardCharsets.UTF_8);
            Path target = session.getPptdProjectDir().resolve(relative).normalize();
            Files.write(target, encoded);
        }
        session.setCreationReady(true);
        session.setStatus("queued");
        session.setProgressStage("queued");
        saveMetadata(session);
        executor.execute(() -> runManualVersionTask(session));
        updateQueuePositions();
        return session;
    }

    private void runManualVersionTask(PptGenerationSession session) {
        session.setStatus("generating");
        saveAndProgress(session, 20, "rendering", "正在导出手工编辑版本");
        try {
            agentRunner.finalizePptdProject(session, (event, data) -> handleAgentEvent(session, event, data));
            verifyAgentArtifacts(session);
            session.setStatus("completed");
            session.setProgress(100);
            session.setProgressStage("completed");
            session.setCompletedAt(System.currentTimeMillis());
            saveMetadata(session);
            emit(session, "done", Map.of("taskId", session.getTaskId(), "qaValid", session.isQaValid()));
            completeEmitters(session.getTaskId());
        } catch (Exception e) {
            failTask(session, e);
        } finally {
            cleanupHistory();
        }
    }

    private void requireCompletedPptd(PptGenerationSession session) {
        if (session == null || !"completed".equals(session.getStatus()) || !"codex-pptd".equals(session.getEngine())
                || !Files.isDirectory(session.getPptdProjectDir())) {
            throw new IllegalArgumentException("PPTD 项目不可用");
        }
    }

    public Path previewImage(PptGenerationSession session, String fileName) {
        if (session == null || !"completed".equals(session.getStatus())) {
            throw new IllegalArgumentException("PPT 尚未生成完成");
        }
        String name = fileName == null ? "" : fileName.trim();
        if (name.isBlank() || name.contains("/") || name.contains("\\")
                || !name.equals(Path.of(name).getFileName().toString())) {
            throw new IllegalArgumentException("预览素材不存在");
        }
        Path root = session.getPreviewDir().toAbsolutePath().normalize();
        Path image = root.resolve(name).normalize();
        if (!image.startsWith(root) || !Files.isRegularFile(image)) {
            throw new IllegalArgumentException("预览素材不存在");
        }
        return image;
    }

    PptGenerationSession getSession(String taskId) {
        updateQueuePositions();
        return sessions.get(taskId);
    }

    public boolean canAccess(PptGenerationSession session, AuthUser user) {
        return session != null && user != null && (user.isRoot() || session.getUserId() == user.id());
    }

    public PptGenerationSession getAuthorizedSession(String taskId, String accessToken) {
        updateQueuePositions();
        return requireAuthorizedSession(taskId, accessToken);
    }

    public List<PptGenerationSession> getRecentSessions(String accessTokens) {
        updateQueuePositions();
        Set<String> allowed = parseAccessTokens(accessTokens);
        if (allowed.isEmpty()) return List.of();
        return terminalSessions()
                .filter(session -> allowed.contains(session.getAccessToken()))
                .limit(maxPerUserHistory()).toList();
    }

    public List<PptGenerationSession> getRecentSessions(AuthUser user, String accessTokens) {
        Set<String> allowed = parseAccessTokens(accessTokens);
        if (user != null && user.isRoot()) return recent().toList();
        return terminalSessions()
                .filter(session -> (user != null && session.getUserId() == user.id())
                        || allowed.contains(session.getAccessToken()))
                .limit(maxPerUserHistory()).toList();
    }

    private Stream<PptGenerationSession> recent() {
        return terminalSessions().limit(maxTotalHistory());
    }

    private Stream<PptGenerationSession> terminalSessions() {
        updateQueuePositions();
        return sessions.values().stream()
                .filter(session -> Set.of("completed", "error").contains(session.getStatus()))
                .sorted(Comparator.comparingLong(PptGenerationSession::getCreatedAt).reversed());
    }

    private int maxPerUserHistory() {
        return runtimeConfig == null ? Math.max(1, config.getMaxHistory()) : runtimeConfig.pptMaxHistory();
    }

    private int maxTotalHistory() {
        int fallback = Math.max(1, config.getMaxGlobalHistory());
        return runtimeConfig == null ? fallback : runtimeConfig.pptMaxGlobalHistory();
    }

    Path getOutput(String taskId) {
        return getOutput(requireSession(taskId));
    }

    public Path getOutput(String taskId, String accessToken) {
        return getOutput(requireAuthorizedSession(taskId, accessToken));
    }

    private Path getOutput(PptGenerationSession session) {
        if (!"completed".equals(session.getStatus())) throw new IllegalStateException("PPT 尚未生成完成");
        if (!Files.isRegularFile(session.getOutputPath())) throw new IllegalStateException("PPT 文件不存在，请重新生成");
        return session.getOutputPath();
    }

    public void subscribe(String taskId, String accessToken, SseEmitter emitter) {
        try {
            subscribe(requireAuthorizedSession(taskId, accessToken), emitter);
        } catch (IllegalArgumentException e) {
            sendAndComplete(emitter, "task-error", Map.of("message", "任务不存在"));
        }
    }

    public void subscribe(PptGenerationSession session, SseEmitter emitter) {
        if (session == null) {
            sendAndComplete(emitter, "task-error", Map.of("message", "任务不存在"));
            return;
        }
        emitters.computeIfAbsent(session.getTaskId(), ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> removeEmitter(session.getTaskId(), emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        sendSnapshot(session, emitter);
    }

    private void validateSourceFile(MultipartFile file) {
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (SOURCE_SUFFIXES.stream().noneMatch(name::endsWith)) {
            throw new IllegalArgumentException("资料文件支持 PDF、DOCX、PPTX、XLSX、TXT、MD、CSV 或 HTML");
        }
        if (file.getSize() > config.getMaxPaperBytes()) throw new IllegalArgumentException("资料文件超过 30MB 限制");
    }

    private void validateFile(MultipartFile file, String suffix, long maxBytes, String label) {
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!name.endsWith(suffix)) throw new IllegalArgumentException(label + "仅支持 " + suffix + " 格式");
        if (file.getSize() > maxBytes) throw new IllegalArgumentException(label + "超过 30MB 限制");
    }

    private void copyUpload(MultipartFile file, Path target) throws IOException {
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean hasPaper(PptGenerationSession session) {
        return session.getPaperFileName() != null && Files.isRegularFile(session.getPaperPath());
    }

    private boolean hasTemplate(PptGenerationSession session) {
        return session.getTemplateFileName() != null && Files.isRegularFile(session.getTemplatePath());
    }

    private String validatePrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        if (value.length() > Math.max(100, config.getMaxPromptChars())) {
            throw new IllegalArgumentException("提示词超过 " + config.getMaxPromptChars() + " 字限制");
        }
        return value;
    }

    private String validateRevisionPrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        if (value.length() > 4000) throw new IllegalArgumentException("二次修改要求不能超过 4000 字");
        return value;
    }

    private String normalizeTemplateKey(String templateKey, String outputFormat) {
        String fallback = "html".equals(outputFormat) ? "html-reveal-white" : "pptd-navy-cyan-technology";
        String value = templateKey == null || templateKey.isBlank() ? fallback : templateKey.trim();
        return templates().stream().anyMatch(item -> value.equals(item.get("key"))
                && item.get("formats") instanceof List<?> formats
                && formats.contains(outputFormat)) ? value : fallback;
    }

    private String normalizeOutputFormat(String outputFormat) {
        String value = outputFormat == null ? "pptx" : outputFormat.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("pptx", "html").contains(value)) throw new IllegalArgumentException("输出格式只能是 PPTX 或 HTML");
        return value;
    }

    private String normalizeResearchMode(String researchMode) {
        String value = researchMode == null ? "auto" : researchMode.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("auto", "off").contains(value)) throw new IllegalArgumentException("联网研究模式只能是 auto 或 off");
        return value;
    }

    private String normalizeVisualMode(String visualMode) {
        String value = visualMode == null ? "best_effort" : visualMode.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("best_effort", "strict").contains(value)) {
            throw new IllegalArgumentException("配图模式只能是 best_effort 或 strict");
        }
        return value;
    }

    private String normalizeMotionMode(String motionMode, String outputFormat) {
        String value = motionMode == null ? "auto" : motionMode.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("auto", "subtle", "expressive", "off").contains(value)) {
            throw new IllegalArgumentException("HTML 动效模式只能是 auto、subtle、expressive 或 off");
        }
        return "html".equals(outputFormat) ? value : "off";
    }

    private String normalizeImageGenerationMode(String imageGenerationMode, String outputFormat) {
        String value = imageGenerationMode == null ? "off" : imageGenerationMode.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("off", "supplement", "prefer").contains(value)) {
            throw new IllegalArgumentException("AI 生图模式只能是 off、supplement 或 prefer");
        }
        // HTML Codex plans use the controlled visual-search prefetcher; paid Images API
        // generation remains a PPTX-only option until its HTML credit/UI contract exists.
        return "pptx".equals(outputFormat) ? value : "off";
    }

    private int normalizeRequestedPageCount(Integer requestedPageCount) {
        if (requestedPageCount == null || requestedPageCount == 0) return 0;
        if (requestedPageCount < 3 || requestedPageCount > 30) {
            throw new IllegalArgumentException("PPT 页数请设置在 3 到 30 页之间");
        }
        return requestedPageCount;
    }

    private int normalizeRequestedImageGenerationCount(Integer requestedCount, String mode, String outputFormat) {
        if (!"pptx".equals(outputFormat) || "off".equals(mode) || requestedCount == null || requestedCount == 0) return 0;
        if (requestedCount < 1 || requestedCount > 4) {
            throw new IllegalArgumentException("AI 生图数量请设置在 1 到 4 张之间");
        }
        int configuredMaximum = runtimeConfig == null ? 4 : Math.max(1, Math.min(4, runtimeConfig.imageGenerationMaxImages()));
        int modeMaximum = "supplement".equals(mode) ? Math.min(2, configuredMaximum) : configuredMaximum;
        if (requestedCount > modeMaximum) {
            throw new IllegalArgumentException("当前 AI 生图模式最多可生成 " + modeMaximum + " 张");
        }
        return requestedCount;
    }

    private String normalizeFontFamily(String fontFamily) {
        String value = fontFamily == null ? "" : fontFamily.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "microsoft yahei", "微软雅黑", "microsoft-yahei", "yahei" -> "Microsoft YaHei";
            case "noto sans cjk sc", "noto-sans-cjk-sc" -> "Noto Sans CJK SC";
            case "pingfang sc", "苹方", "pingfang-sc" -> "PingFang SC";
            case "source han sans sc", "思源黑体", "source-han-sans-sc" -> "Source Han Sans SC";
            case "simsun", "宋体", "simsun-sc" -> "SimSun";
            default -> "Microsoft YaHei";
        };
    }

    private void assertDeploymentNotLocked() {
        String configured = System.getenv("DEPLOYMENT_LOCK_PATH");
        Path lock = configured == null || configured.isBlank()
                ? Path.of("").toAbsolutePath().resolve("../.run/deployment.lock").normalize()
                : Path.of(configured).toAbsolutePath().normalize();
        if (Files.exists(lock)) {
            throw new IllegalStateException("系统正在发布更新，请稍后重新提交任务");
        }
    }

    private String normalizeClientRequestId(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= 128 && normalized.matches("[A-Za-z0-9._:-]+") ? normalized : "";
    }

    private String claimIdempotency(AuthUser user, String requestId) {
        return user == null || requestId.isBlank() ? null : user.id() + ":" + requestId;
    }

    private PptGenerationSession claimedSession(String claimKey, long userId, String requestId) {
        String taskId = idempotencyClaims.get(claimKey);
        PptGenerationSession claimed = taskId == null || "__creating__".equals(taskId) ? null : sessions.get(taskId);
        if (claimed != null && !"error".equals(claimed.getStatus())) return claimed;
        if (claimed != null && claimed.isRefundPending()) {
            throw new IllegalStateException("上一次任务正在补偿额度，请稍后再试");
        }
        if (taskId != null && !"__creating__".equals(taskId)) idempotencyClaims.remove(claimKey, taskId);
        return sessions.values().stream()
                .filter(session -> session.getUserId() == userId)
                .filter(session -> requestId.equals(session.getClientRequestId()))
                .filter(session -> !"error".equals(session.getStatus()))
                .max(Comparator.comparingLong(PptGenerationSession::getCreatedAt)).orElse(null);
    }

    private String outputFileName(PptGenerationSession session) {
        return "AI生成PPT-" + session.getTaskId()
                + ("html".equalsIgnoreCase(session.getOutputFormat()) ? ".html" : ".pptx");
    }

    private void saveAndProgress(PptGenerationSession session, double progress, String stage, String message) {
        session.setProgress(progress);
        session.setProgressStage(stage);
        saveMetadata(session);
        emit(session, "progress", Map.of("progress", progress, "stage", stage,
                "stageLabel", stageLabel(stage), "message", message));
    }

    private void failTask(PptGenerationSession session, Exception error) {
        log.error("PPT Agent 任务失败: taskId={}", session.getTaskId(), error);
        session.setStatus("error");
        session.setProgressStage("error");
        session.setErrorMessage(error.getMessage() == null ? "PPT 生成失败" : error.getMessage());
        saveMetadata(session);
        try {
            refundIfNeeded(session, "PPT 生成失败自动退回额度");
        } catch (Exception refundError) {
            session.setRefundPending(true);
            session.setRefundError(trimLog(refundError.getMessage()));
            saveMetadata(session);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", session.getErrorMessage());
        if (session.isRefundPending()) payload.put("refundPending", true);
        emit(session, "task-error", payload);
        completeEmitters(session.getTaskId());
    }

    private void handleCreationFailure(PptGenerationSession session, String claimKey,
                                       Exception cause, String refundReason) {
        boolean keep = persistCreationFailure(session, cause, refundReason);
        if (!keep) {
            sessions.remove(session.getTaskId());
            deleteRecursively(session.getTaskDir());
        }
        if (claimKey != null) {
            if (keep) idempotencyClaims.put(claimKey, session.getTaskId());
            else {
                idempotencyClaims.remove(claimKey, session.getTaskId());
                idempotencyClaims.remove(claimKey, "__creating__");
            }
        }
    }

    private boolean persistCreationFailure(PptGenerationSession session, Exception cause, String refundReason) {
        session.setStatus("error");
        session.setProgressStage("error");
        session.setErrorMessage(cause.getMessage() == null ? "PPT 任务创建失败" : cause.getMessage());
        boolean keep = false;
        try {
            refundIfNeeded(session, refundReason);
        } catch (Exception refundError) {
            keep = true;
            session.setRefundPending(true);
            session.setRefundError(trimLog(refundError.getMessage()));
        }
        if (keep) sessions.put(session.getTaskId(), session);
        saveMetadata(session);
        return keep;
    }

    private void refundIfNeeded(PptGenerationSession session, String reason) {
        if (quotaService == null || session.getCreditTransactionId() == null || session.isCreditRefunded()) return;
        quotaService.refund(session.getCreditTransactionId(), reason);
        session.setCreditRefunded(true);
        session.setRefundPending(false);
        session.setRefundError(null);
        saveMetadata(session);
    }

    private void sendSnapshot(PptGenerationSession session, SseEmitter emitter) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", session.getTaskId());
        data.put("status", session.getStatus());
        data.put("progress", session.getProgress());
        data.put("stage", session.getProgressStage());
        data.put("stageLabel", stageLabel(session.getProgressStage()));
        data.put("queuePosition", session.getQueuePosition());
        data.put("sourceCount", session.getSourceCount());
        data.put("iteration", session.getAgentIteration());
        data.put("message", session.getErrorMessage() == null ? "" : session.getErrorMessage());
        if ("completed".equals(session.getStatus())) send(emitter, "done", data);
        else if ("error".equals(session.getStatus())) send(emitter, "task-error", data);
        else send(emitter, "progress", data);
    }

    private void emit(PptGenerationSession session, String eventName, Object data) {
        List<SseEmitter> list = emitters.get(session.getTaskId());
        if (list == null) return;
        list.removeIf(emitter -> !send(emitter, eventName, data));
        if (list.isEmpty()) emitters.remove(session.getTaskId(), list);
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
        List<SseEmitter> list = emitters.get(taskId);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) emitters.remove(taskId, list);
    }

    private void completeEmitters(String taskId) {
        List<SseEmitter> list = emitters.remove(taskId);
        if (list != null) list.forEach(SseEmitter::complete);
    }

    private void updateQueuePositions() {
        List<PptGenerationSession> queued = sessions.values().stream()
                .filter(session -> "queued".equals(session.getStatus()))
                .sorted(Comparator.comparingLong(PptGenerationSession::getCreatedAt)).toList();
        for (int index = 0; index < queued.size(); index++) queued.get(index).setQueuePosition(index + 1);
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
                    }
                    if ("creating".equals(session.getStatus())) {
                        reconcileCreatingSession(session);
                    } else if (!Set.of("completed", "error").contains(session.getStatus())) {
                        session.setStatus("queued");
                        session.setProgressStage("queued");
                        session.setErrorMessage(null);
                        session.setQueuePosition(0);
                    }
                    if ("completed".equals(session.getStatus()) && !Files.isRegularFile(session.getOutputPath())) {
                        session.setStatus("error");
                        session.setProgressStage("error");
                        session.setErrorMessage("任务输出文件缺失，请重新生成");
                    }
                    saveMetadata(session);
                    sessions.put(session.getTaskId(), session);
                    if (session.getUserId() > 0 && session.getClientRequestId() != null
                            && (!"error".equals(session.getStatus()) || session.isRefundPending())) {
                        idempotencyClaims.put(session.getUserId() + ":" + session.getClientRequestId(), session.getTaskId());
                    }
                } catch (Exception e) {
                    log.warn("读取 PPT Agent 任务记录失败: {}", metadata, e);
                }
            });
        } catch (IOException e) {
            log.warn("读取 PPT Agent 任务目录失败: {}", storageDir, e);
        }
    }

    private void reconcileCreatingSession(PptGenerationSession session) {
        if (session.isQuotaRequired() && session.getCreditTransactionId() == null && quotaService != null) {
            session.setCreditTransactionId(quotaService.findSpendTransactionId(session.getTaskId()));
        }
        if (!session.isCreationReady()) {
            session.setStatus("error");
            session.setProgressStage("error");
            session.setErrorMessage("服务在任务资料写入完成前重启，请重新提交");
            try {
                refundIfNeeded(session, "PPT 创建中断自动补偿额度");
            } catch (RuntimeException error) {
                session.setRefundPending(true);
                session.setRefundError(trimLog(error.getMessage()));
            }
            return;
        }
        if (session.isQuotaRequired() && session.getCreditTransactionId() == null) {
            session.setStatus("error");
            session.setProgressStage("error");
            session.setErrorMessage("服务在额度扣减完成前重启，请重新提交");
            return;
        }
        session.setStatus("queued");
        session.setProgressStage("queued");
        session.setErrorMessage(null);
        session.setQueuePosition(0);
    }

    private void recoverPendingSessions() {
        sessions.values().stream().filter(session -> "queued".equals(session.getStatus()))
                .sorted(Comparator.comparingLong(PptGenerationSession::getCreatedAt))
                .forEach(session -> {
                    try {
                        executor.execute(() -> runGenerationTask(session));
                    } catch (RejectedExecutionException e) {
                        failTask(session, new IllegalStateException("恢复任务超过有界队列容量", e));
                    }
                });
        updateQueuePositions();
    }

    private void saveMetadata(PptGenerationSession session) {
        try {
            Path temp = session.getMetadataPath().resolveSibling("task.json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), session);
            try {
                Files.move(temp, session.getMetadataPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, session.getMetadataPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("保存 PPT Agent 任务记录失败: taskId={}", session.getTaskId(), e);
        }
    }

    private void reconcilePendingRefunds() {
        if (quotaService == null) return;
        sessions.values().stream().filter(session -> "error".equals(session.getStatus()))
                .filter(session -> session.getCreditTransactionId() != null && !session.isCreditRefunded())
                .forEach(session -> {
                    try {
                        refundIfNeeded(session, "PPT 生成失败自动补偿额度");
                    } catch (Exception e) {
                        session.setRefundPending(true);
                        session.setRefundError(trimLog(e.getMessage()));
                        saveMetadata(session);
                    }
                });
    }

    public void cleanupHistory() {
        List<PptGenerationSession> terminal = sessions.values().stream()
                .filter(session -> Set.of("completed", "error").contains(session.getStatus()))
                .filter(session -> !session.isRefundPending())
                .sorted(Comparator.comparingLong(PptGenerationSession::getCreatedAt).reversed()).toList();
        Map<Long, Integer> perUserCounts = new LinkedHashMap<>();
        Set<String> keep = new HashSet<>();
        for (PptGenerationSession session : terminal) {
            long userId = session.getUserId();
            int count = perUserCounts.getOrDefault(userId, 0);
            if (count < maxPerUserHistory()) {
                perUserCounts.put(userId, count + 1);
                keep.add(session.getTaskId());
            }
        }
        int globalCount = 0;
        for (PptGenerationSession session : terminal) {
            if (!keep.contains(session.getTaskId())) continue;
            if (globalCount++ < maxTotalHistory()) continue;
            keep.remove(session.getTaskId());
        }
        for (PptGenerationSession session : terminal) {
            if (keep.contains(session.getTaskId())) continue;
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
                    log.warn("清理 PPT Agent 任务文件失败: {}", item, e);
                }
            });
        } catch (IOException e) {
            log.warn("清理 PPT Agent 任务目录失败: {}", path, e);
        }
    }

    private void copyOptional(Path source, Path target) throws IOException {
        if (source == null || target == null || !Files.isRegularFile(source)) return;
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void copyDirectoryContents(Path source, Path target) throws IOException {
        if (source == null || !Files.isDirectory(source)) return;
        Path targetRoot = target.toAbsolutePath().normalize();
        Files.createDirectories(targetRoot);
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = targetRoot.resolve(source.relativize(path)).normalize();
                if (!destination.startsWith(targetRoot)) throw new IOException("修订素材路径越界");
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private PptGenerationSession requireSession(String taskId) {
        PptGenerationSession session = sessions.get(taskId);
        if (session == null) throw new IllegalArgumentException("任务不存在");
        return session;
    }

    private PptGenerationSession requireAuthorizedSession(String taskId, String token) {
        PptGenerationSession session = requireSession(taskId);
        if (!tokenMatches(session.getAccessToken(), token)) throw new IllegalArgumentException("任务不存在");
        return session;
    }

    private boolean tokenMatches(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.trim().getBytes(StandardCharsets.UTF_8));
    }

    private Set<String> parseAccessTokens(String values) {
        if (values == null || values.isBlank()) return Set.of();
        Set<String> tokens = new HashSet<>();
        for (String token : values.split(",")) {
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

    private String trim(String value, int max) {
        String safe = value == null ? "" : value;
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private String trimLog(String value) {
        String safe = value == null ? "" : value;
        return safe.length() <= 2000 ? safe : safe.substring(safe.length() - 2000);
    }

    public String stageLabel(String stage) {
        return switch (stage == null ? "" : stage) {
            case "queued" -> "等待后台生成";
            case "researching" -> "Agent 联网研究";
            case "planning" -> "Agent 规划叙事";
            case "authoring" -> "Agent 编排演示";
            case "rendering" -> "渲染真实预览";
            case "reviewing" -> "检查版式与引用";
            case "revising" -> "Agent 返修";
            case "completed" -> "演示生成完成";
            case "error" -> "演示生成失败";
            default -> "处理中";
        };
    }
}
