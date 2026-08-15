package com.web.backen.ppt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.RuntimeConfigService;
import com.web.backen.config.PptGenerationConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/** Authorized PPTD generator. Codex can write only a disposable task workspace. */
@Component
public class PptCodexRunner {
    private static final Logger log = LoggerFactory.getLogger(PptCodexRunner.class);
    private static final int MAX_VISUAL_PREFLIGHT_REPAIRS = 2;
    private final PptGenerationConfig config;
    private final RuntimeConfigService runtime;
    private final ObjectMapper objectMapper;
    private final PptImageGenerationService imageGeneration;
    private final Set<Process> active = ConcurrentHashMap.newKeySet();

    public PptCodexRunner(PptGenerationConfig config, RuntimeConfigService runtime, ObjectMapper objectMapper,
                          PptImageGenerationService imageGeneration) {
        this.config = config;
        this.runtime = runtime;
        this.objectMapper = objectMapper;
        this.imageGeneration = imageGeneration;
    }

    public void run(PptGenerationSession session, BiConsumer<String, Map<String, Object>> events)
            throws IOException, InterruptedException {
        String apiKey = runtime.codexPptKey();
        Path vendor = resolve(config.getCodexVendorRoot());
        Path skill = vendor.resolve("skills/open-kimi-ppt");
        Path codex = resolve(config.getCodexCommand());
        Path finalizeScript = resolve(config.getCodexFinalizeScript());
        Path visualPrefetchScript = resolve(config.getVisualPrefetchScript());
        if (!Files.isRegularFile(codex) || !Files.isExecutable(codex)) throw new IllegalStateException("锁定的 Codex CLI 不可用: " + codex);
        if (!Files.isRegularFile(skill.resolve("SKILL.md"))) throw new IllegalStateException("open-kimi-ppt Skill 不完整");
        if (!Files.isRegularFile(finalizeScript)) throw new IllegalStateException("PPTD 固定导出桥不存在");

        int searchedVisuals = 0;
        if (!"off".equalsIgnoreCase(session.getResearchMode())) {
            try {
                searchedVisuals = prefetchVisualAssets(session, visualPrefetchScript, events);
            } catch (Exception error) {
                log.warn("Controlled PPT visual prefetch failed: taskId={}, reason={}",
                        session.getTaskId(), sanitizeDiagnostic(error.getMessage()));
                if ("strict".equals(session.getVisualMode())) {
                    throw new IllegalStateException("网络图片搜索未完成，严格配图任务已停止，请检查搜索配置后重试");
                }
                events.accept("researching", Map.of("progress", 8, "message", "网络图片暂不可用，将使用资料与清晰版式继续"));
            }
            if ("strict".equals(session.getVisualMode()) && searchedVisuals == 0) {
                throw new IllegalStateException("没有找到可安全使用的相关网络图片，严格配图任务已停止");
            }
        }

        Path workspace = Files.createTempDirectory("web-ppt-codex-").toAbsolutePath().normalize();
        setOwnerOnly(workspace);
        Path codexHome = workspace.resolve(".codex-home");
        Path input = workspace.resolve("input");
        Path deck = workspace.resolve("deck");
        Files.createDirectories(codexHome);
        Files.createDirectories(input);
        Files.createDirectories(deck);
        setOwnerOnly(codexHome);
        try {
            copyTree(skill, workspace.resolve("skill"), config.getCodexMaxProjectFiles(), config.getCodexMaxProjectBytes());
            copyIfPresent(session.getTaskDir().resolve("source.txt"), input.resolve("source.txt"));
            copyIfPresent(session.getPaperPath(), input.resolve("source" + sourceSuffix(session.getPaperFileName())));
            copyIfPresent(session.getTemplatePath(), input.resolve("template.pptx"));
            if (Files.isDirectory(session.getImagesDir())) {
                copyTree(session.getImagesDir(), input.resolve("images"),
                        config.getCodexMaxProjectFiles(), config.getCodexMaxProjectBytes());
            }
            Path webImages = session.getTaskDir().resolve("web-images");
            if (Files.isDirectory(webImages)) {
                copyTree(webImages, input.resolve("web-images"),
                        config.getCodexMaxProjectFiles(), config.getCodexMaxProjectBytes());
            }
            Path previousProject = session.getTaskDir().resolve("previous-pptd-project");
            if (Files.isDirectory(previousProject)) {
                copyTree(previousProject, input.resolve("previous-pptd-project"),
                        config.getCodexMaxProjectFiles(), config.getCodexMaxProjectBytes());
            }
            Files.writeString(input.resolve("request.txt"), session.getPrompt(), StandardCharsets.UTF_8);
            Path generatedImages = input.resolve("generated-images");
            imageGeneration.generate(session, generatedImages, events);
            if (Files.isDirectory(generatedImages)) {
                copyTree(generatedImages, session.getTaskDir().resolve("generated-images"),
                        config.getCodexMaxProjectFiles(), config.getCodexMaxProjectBytes());
            }
            copyIfPresent(session.getTaskDir().resolve("presentation-plan.json"), input.resolve("presentation-plan.json"));

            String providerBaseUrl = runtime.codexPptProviderBaseUrl();
            AuthSource auth = prepareAuth(codex, codexHome, apiKey);
            boolean customProvider = !providerBaseUrl.isBlank() && !auth.localCli();
            if (customProvider) writeCcswitchProviderConfig(codexHome, providerBaseUrl,
                    runtime.codexPptModel(), runtime.codexPptReasoningEffort());
            events.accept("planning", Map.of("progress", 10, "message",
                    auth.localCli() ? "正在复用本机 Codex CLI 登录态" : "正在启动隔离的 Codex PPTD Agent"));
            String prompt = prompt(session);
            List<String> command = execCommand(codex, workspace, runtime.codexPptModel(),
                    runtime.codexPptReasoningEffort(), "workspace-write", auth.localCli(), customProvider);
            StringBuilder codexEvents = new StringBuilder();
            try {
                codexEvents.append(run(command, workspace, codexHome, prompt,
                        Duration.ofSeconds(config.getCodexTimeoutSeconds()), events, true));
            } catch (CodexProcessException failure) {
                appendBounded(codexEvents, failure.processLog());
                logCodexDiagnostic(session, "process-exit-" + failure.exitCode(), codexEvents);
                throw new IllegalStateException("Codex PPT 生成未完成，请稍后重试");
            }
            Path manifest = singleManifest(deck);
            if (manifest == null) {
                logCodexDiagnostic(session, "missing-deck-manifest", codexEvents);
                throw new IllegalStateException("Codex 未生成完整 PPTD 项目，请重试");
            }
            Path target = session.getTaskDir().resolve("pptd-project");
            try {
                finalizeWithVisualRepairs(session, events, finalizeScript, vendor, workspace, codexHome,
                        command, deck, target, codexEvents);
            } catch (CodexProcessException failure) {
                appendBounded(codexEvents, failure.processLog());
                // A relay can return a wrapper-level non-zero exit after the repair
                // agent has already written its deck. Never trust that deck directly:
                // retry the fixed exporter and every deterministic gate first. This
                // also makes the recovery independent from transient workspace mount
                // observations inside the repair loop.
                if (finalizeRetainedDeck(session, events, finalizeScript, vendor, deck, target)) {
                    log.info("Codex repair returned exit {} but the retained project passed fixed export: taskId={}",
                            failure.exitCode(), session.getTaskId());
                    return;
                }
                logCodexDiagnostic(session, "repair-process-exit-" + failure.exitCode(), codexEvents);
                throw new IllegalStateException("Codex PPT 修复未完成，请稍后重试");
            }
        } finally {
            deleteTree(workspace);
        }
    }

    /**
     * HTML authoring uses the same isolated Codex profile as PPTD, but the agent
     * may only write a bounded JSON deck plan. A repository-owned renderer then
     * validates that plan, builds the self-contained reveal.js file and performs
     * real-browser QA. No LLM credential or arbitrary agent HTML reaches delivery.
     */
    public void runHtml(PptGenerationSession session, BiConsumer<String, Map<String, Object>> events)
            throws IOException, InterruptedException {
        String apiKey = runtime.codexPptKey();
        Path codex = resolve(config.getCodexCommand());
        Path skill = resolve(config.getCodexHtmlSkillRoot());
        Path finalizeScript = resolve(config.getCodexHtmlFinalizeScript());
        Path visualPrefetchScript = resolve(config.getVisualPrefetchScript());
        if (!Files.isRegularFile(codex) || !Files.isExecutable(codex)) {
            throw new IllegalStateException("锁定的 Codex CLI 不可用: " + codex);
        }
        if (!Files.isRegularFile(skill.resolve("SKILL.md"))) throw new IllegalStateException("HTML 演示 Skill 不完整");
        if (!Files.isRegularFile(finalizeScript)) throw new IllegalStateException("HTML 固定渲染桥不存在");

        int searchedVisuals = 0;
        if (!"off".equalsIgnoreCase(session.getResearchMode())) {
            try {
                searchedVisuals = prefetchVisualAssets(session, visualPrefetchScript, events);
            } catch (Exception error) {
                log.warn("Controlled HTML visual prefetch failed: taskId={}, reason={}",
                        session.getTaskId(), sanitizeDiagnostic(error.getMessage()));
                if ("strict".equals(session.getVisualMode())) {
                    throw new IllegalStateException("网络图片搜索未完成，严格配图任务已停止，请检查搜索配置后重试");
                }
                events.accept("researching", Map.of("progress", 8, "message", "网络图片暂不可用，将使用资料与清晰版式继续"));
            }
            if ("strict".equals(session.getVisualMode()) && searchedVisuals == 0) {
                throw new IllegalStateException("没有找到可安全使用的相关网络图片，严格配图任务已停止");
            }
        }

        Path workspace = Files.createTempDirectory("web-html-codex-").toAbsolutePath().normalize();
        setOwnerOnly(workspace);
        Path codexHome = workspace.resolve(".codex-home");
        Path input = workspace.resolve("input");
        Path output = workspace.resolve("output");
        Files.createDirectories(codexHome);
        Files.createDirectories(input);
        Files.createDirectories(output);
        setOwnerOnly(codexHome);
        try {
            copyTree(skill, workspace.resolve("skill"), config.getCodexMaxProjectFiles(), config.getCodexMaxProjectBytes());
            copyIfPresent(session.getTaskDir().resolve("source.txt"), input.resolve("source.txt"));
            if (Files.isDirectory(session.getImagesDir())) {
                copyTree(session.getImagesDir(), input.resolve("images"),
                        config.getCodexMaxProjectFiles(), config.getCodexMaxProjectBytes());
            }
            Path webImages = session.getTaskDir().resolve("web-images");
            if (Files.isDirectory(webImages)) {
                copyTree(webImages, input.resolve("web-images"),
                        config.getCodexMaxProjectFiles(), config.getCodexMaxProjectBytes());
            }
            copyIfPresent(session.getTaskDir().resolve("previous-agent-plan.json"), input.resolve("previous-agent-plan.json"));
            Files.writeString(input.resolve("request.txt"), session.getPrompt(), StandardCharsets.UTF_8);
            Path generatedImages = input.resolve("generated-images");
            imageGeneration.generate(session, generatedImages, events);
            if (Files.isDirectory(generatedImages)) {
                copyTree(generatedImages, session.getTaskDir().resolve("generated-images"),
                        config.getCodexMaxProjectFiles(), config.getCodexMaxProjectBytes());
            }
            copyIfPresent(session.getTaskDir().resolve("presentation-plan.json"), input.resolve("presentation-plan.json"));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(input.resolve("assets.json").toFile(), htmlAssets(session));

            String providerBaseUrl = runtime.codexPptProviderBaseUrl();
            AuthSource auth = prepareAuth(codex, codexHome, apiKey);
            boolean customProvider = !providerBaseUrl.isBlank() && !auth.localCli();
            if (customProvider) writeCcswitchProviderConfig(codexHome, providerBaseUrl,
                    runtime.codexPptModel(), runtime.codexPptReasoningEffort());
            events.accept("planning", Map.of("progress", 12, "message",
                    auth.localCli() ? "正在复用本机 Codex CLI 编排 HTML 演示" : "正在启动隔离的 Codex HTML Agent"));
            List<String> command = execCommand(codex, workspace, runtime.codexPptModel(),
                    runtime.codexPptReasoningEffort(), "workspace-write", auth.localCli(), customProvider);
            String codexLog;
            try {
                codexLog = run(command, workspace, codexHome, htmlPrompt(session),
                        Duration.ofSeconds(config.getCodexTimeoutSeconds()), events, true);
            } catch (CodexProcessException failure) {
                StringBuilder diagnostic = new StringBuilder(failure.processLog());
                logCodexDiagnostic(session, "html-process-exit-" + failure.exitCode(), diagnostic);
                throw new IllegalStateException("Codex HTML 生成未完成，请稍后重试");
            }
            Path plan = output.resolve("agent-plan.json");
            if (!Files.isRegularFile(plan) || Files.isSymbolicLink(plan)
                    || Files.size(plan) < 2 || Files.size(plan) > 1024L * 1024) {
                logCodexDiagnostic(session, "missing-html-plan", new StringBuilder(codexLog));
                throw new IllegalStateException("Codex 未生成完整 HTML 演示计划，请重试");
            }
            Path retainedPlan = session.getTaskDir().resolve("codex-html-plan.json");
            Files.copy(plan, retainedPlan, StandardCopyOption.REPLACE_EXISTING);
            events.accept("authoring", Map.of("progress", 64, "message", "正在用固定 reveal.js 渲染器生成演示"));
            Path result = session.getTaskDir().resolve("html-finalize-result.json");
            Files.deleteIfExists(result);
            List<String> finalizeCommand = List.of(config.getAgentCommand(),
                    "--max-old-space-size=" + config.getAgentNodeMaxOldSpaceMb(), finalizeScript.toString(),
                    session.getTaskDir().toAbsolutePath().normalize().toString(), retainedPlan.toString(),
                    session.getTemplateKey(), session.getFontFamily(), session.getMotionMode(),
                    session.getVisualMode(), result.toString());
            Map<String, String> env = new LinkedHashMap<>();
            putIfConfigured(env, "PPT_AGENT_CHROME", config.getChromeCommand());
            run(finalizeCommand, finalizeScript.getParent(), null, "", Duration.ofSeconds(600), events, false, env);
            JsonNode finalized = objectMapper.readTree(result.toFile());
            int sourceCount = finalized.path("sourceCount").asInt(0);
            boolean qaValid = finalized.path("qa").path("valid").asBoolean(false);
            events.accept("done", Map.of("progress", 100, "message", "Codex HTML 演示已完成固定渲染与浏览器质检",
                    "sourceCount", sourceCount, "qa", Map.of("valid", qaValid)));
            Files.writeString(session.getTaskDir().resolve("agent.log"),
                    tail(codexLog, (int) Math.min(64 * 1024L, config.getCodexMaxLogBytes())), StandardCharsets.UTF_8);
        } finally {
            deleteTree(workspace);
        }
    }

    private Map<String, Object> htmlAssets(PptGenerationSession session) throws IOException {
        List<Map<String, Object>> assets = new ArrayList<>();
        Path generatedManifest = session.getTaskDir().resolve("generated-images/generated-image-manifest.json");
        if (Files.isRegularFile(generatedManifest) && Files.size(generatedManifest) <= 512L * 1024) {
            JsonNode images = objectMapper.readTree(generatedManifest.toFile()).path("images");
            if (images.isArray()) for (JsonNode image : images) {
                String id = image.path("id").asText("");
                String fileName = image.path("fileName").asText("");
                if (!id.matches("GPT\\d{2}") || !fileName.matches("gpt-\\d+\\.png")) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id); item.put("fileName", fileName); item.put("origin", "generated");
                item.put("slideId", image.path("slideId").asText(""));
                item.put("slideTitle", image.path("slideTitle").asText(""));
                item.put("rightsStatus", "generated"); assets.add(item);
            }
        }
        if (Files.isDirectory(session.getImagesDir())) {
            try (Stream<Path> stream = Files.list(session.getImagesDir())) {
                List<Path> uploaded = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().matches("(?i).+\\.(png|jpe?g|gif)$"))
                        .filter(path -> !path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith("paper-page-"))
                        .sorted().limit(8).toList();
                int index = 1;
                for (Path file : uploaded) {
                    if (Files.size(file) > 4L * 1024 * 1024 || !hasAcceptedImageSignature(file)) continue;
                    assets.add(Map.of("id", "U" + String.format("%02d", index++), "fileName", file.getFileName().toString(),
                            "origin", "upload", "rightsStatus", "user-provided"));
                }
            }
        }
        Path manifest = session.getTaskDir().resolve("web-images/image-assets.json");
        if (Files.isRegularFile(manifest) && Files.size(manifest) <= 512L * 1024) {
            JsonNode root = objectMapper.readTree(manifest.toFile());
            JsonNode images = root.isArray() ? root : root.path("images");
            if (images.isArray()) {
                for (JsonNode image : images) {
                    String id = image.path("id").asText("");
                    String fileName = image.path("fileName").asText(image.path("localPath").asText(""));
                    if (!id.matches("(?i)WEB\\d{2}") || !fileName.matches("(?i)WEB\\d{2}\\.(png|jpe?g|gif)")) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", id.toUpperCase(java.util.Locale.ROOT));
                    item.put("fileName", fileName);
                    item.put("title", image.path("title").asText(""));
                    item.put("description", image.path("description").asText(""));
                    item.put("sourceUrl", image.path("sourceUrl").asText(""));
                    item.put("rightsStatus", image.path("rightsStatus").asText("unverified"));
                    assets.add(item);
                    if (assets.size() >= 12) break;
                }
            }
        }
        return Map.of("assets", assets);
    }

    private String htmlPrompt(PptGenerationSession session) {
        return """
                You are the isolated authoring agent for a safe offline HTML presentation service.
                Read ./skill/SKILL.md, ./skill/references/design-and-motion.md and ./skill/references/quality-contract.md completely.
                Read ./input/request.txt, optional ./input/source.txt, ./input/assets.json, and optional
                ./input/previous-agent-plan.json. Create ONLY ./output/agent-plan.json. Do not create HTML,
                CSS, JavaScript, scripts, binaries, packages, or any other output. Do not use network tools,
                browsers, curl, wget, package managers, or execute generated code. The server will validate
                your JSON and render it with its fixed reveal.js implementation.

                Theme: %s. Requested font: %s. Motion mode: %s. Visual mode: %s. Research mode: %s.
                %s
                Assets in ./input/assets.json are the only allowed imageId values. Uxx items are user uploads.
                GPTxx items are slide-bound server-generated visuals: use each only on its declared slideId and never cite it.
                WEBxx items are server-validated search thumbnails; their rightsStatus may be unverified, so
                never call them licensed. Use an image only when its title/description is clearly topical.
                In strict visual mode, at least one non-cover/non-closing page must use a WEBxx image.

                Write a single JSON object with this shape:
                {"title":"...","audience":"...","takeaway":"...","slides":[
                  {"type":"cover|section|content|evidence|comparison|timeline|quote|closing",
                   "layout":"cover|section|statement|image-hero|split|evidence|stats|process|comparison|timeline|quote|gallery|closing",
                   "tone":"base|light|deep","section":"...","title":"...","headline":"...",
                   "bullets":["..."],"items":[{"label":"...","value":"...","detail":"..."}],
                   "slideId":"slide-01","imageId":"U01|WEB01|GPT01|","sourceIds":["WEB01"],"notes":"..."}
                ]}
                Produce 3-30 pages. First page must be cover and last page closing. Use stats only for real
                numbers, process/timeline only for ordered relations, comparison only for genuine sides,
                and image-hero/gallery only for a strong topical image. Vary page silhouettes and deep/light
                rhythm; never repeat one layout three times in a row. Keep each page concise enough for a
                1280x720 viewport: at most 4 bullets normally, 6 only for comparison, with compact complete
                sentences. No visible references page, markdown/LaTeX syntax, placeholders, fabricated facts,
                template sample copy, or raw PDF-page images. Put provenance IDs in sourceIds/notes only.
                If revising, return the complete revised deck, preserving unaffected good pages.
                Finish only after valid JSON exists at exactly ./output/agent-plan.json.
                """.formatted(session.getTemplateKey(), session.getFontFamily(), session.getMotionMode(),
                session.getVisualMode(), session.getResearchMode(), requestedPageCountInstruction(session));
    }

    public void finalizeExisting(PptGenerationSession session, BiConsumer<String, Map<String, Object>> events)
            throws IOException, InterruptedException {
        Path vendor = resolve(config.getCodexVendorRoot());
        Path finalizeScript = resolve(config.getCodexFinalizeScript());
        runFinalize(finalizeScript, session.getTaskDir(), vendor, session.getFontFamily(), events);
    }

    /**
     * Runs the locked search/downloader outside the Codex sandbox. Only validated local
     * images and a provenance manifest cross into the disposable workspace.
     */
    int prefetchVisualAssets(PptGenerationSession session, Path script,
                             BiConsumer<String, Map<String, Object>> events)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(script)) throw new IllegalStateException("PPT 网络配图预取器不存在");
        Path taskDir = session.getTaskDir().toAbsolutePath().normalize();
        Path retained = taskDir.resolve("web-images");
        int existing = retainedVisualCount(retained);
        if (existing > 0) {
            events.accept("researching", Map.of("progress", 8, "message", "正在复用已校验的网络视觉素材",
                    "sourceCount", existing));
            return existing;
        }

        Path request = taskDir.resolve("visual-prefetch-request.json");
        Path staging = Files.createTempDirectory(taskDir, "web-images-prefetch-").toAbsolutePath().normalize();
        try {
            Map<String, Object> payload = Map.of(
                    "prompt", trimForVisualSearch(session.getPrompt()),
                    "maxImages", 6,
                    "maxQueries", 3);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(request.toFile(), payload);
            events.accept("researching", Map.of("progress", 7, "message", "正在通过受控搜索查找相关图片"));

            List<String> command = List.of(config.getAgentCommand(),
                    "--max-old-space-size=" + config.getAgentNodeMaxOldSpaceMb(),
                    script.toString(), request.toString(), staging.toString());
            Map<String, String> env = new LinkedHashMap<>();
            putIfConfigured(env, "PPT_AGENT_TAVILY_ENDPOINT", runtime.tavilyUrl());
            putIfConfigured(env, "PPT_AGENT_TAVILY_KEY", runtime.tavilyKey());
            putIfConfigured(env, "PPT_AGENT_PROXY_URL", outboundProxyUrl());
            run(command, script.getParent().getParent().getParent(), null, "", Duration.ofSeconds(90),
                    (ignored, data) -> { }, false, env);

            int count = retainedVisualCount(staging);
            if (Files.exists(retained)) deleteTree(retained);
            Files.move(staging, retained, StandardCopyOption.REPLACE_EXISTING);
            events.accept("researching", Map.of("progress", 9,
                    "message", count > 0 ? "已准备 " + count + " 张可追溯网络视觉素材" : "未找到合适网络图片，将继续使用资料与版式",
                    "sourceCount", count));
            return count;
        } finally {
            Files.deleteIfExists(request);
            if (Files.exists(staging)) deleteTree(staging);
        }
    }

    private int retainedVisualCount(Path directory) {
        Path manifest = directory.resolve("image-assets.json");
        if (!Files.isRegularFile(manifest)) return 0;
        try {
            Path normalizedDirectory = directory.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(directory) || Files.size(manifest) > 512L * 1024) return 0;
            JsonNode root = objectMapper.readTree(manifest.toFile());
            JsonNode images = root.isArray() ? root : root.path("images");
            if (!images.isArray()) return 0;
            Set<Path> accepted = new java.util.HashSet<>();
            for (JsonNode image : images) {
                String fileName = image.path("fileName").asText(image.path("localPath").asText(""));
                if (fileName.matches("WEB\\d{2}\\.(?i:png|jpe?g|gif)")) {
                    Path file = normalizedDirectory.resolve(fileName).normalize();
                    if (file.startsWith(normalizedDirectory) && Files.isRegularFile(file)
                            && !Files.isSymbolicLink(file) && Files.size(file) <= 4L * 1024 * 1024
                            && hasAcceptedImageSignature(file)) accepted.add(file);
                }
            }
            return accepted.size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean hasAcceptedImageSignature(Path file) throws IOException {
        byte[] header = new byte[12];
        int size;
        try (var input = Files.newInputStream(file)) {
            size = input.read(header);
        }
        boolean png = size >= 8 && header[0] == (byte) 0x89 && header[1] == 0x50
                && header[2] == 0x4e && header[3] == 0x47 && header[4] == 0x0d
                && header[5] == 0x0a && header[6] == 0x1a && header[7] == 0x0a;
        boolean jpeg = size >= 3 && header[0] == (byte) 0xff && header[1] == (byte) 0xd8
                && header[2] == (byte) 0xff;
        boolean gif = size >= 6 && header[0] == 0x47 && header[1] == 0x49 && header[2] == 0x46
                && header[3] == 0x38 && (header[4] == 0x37 || header[4] == 0x39) && header[5] == 0x61;
        return png || jpeg || gif;
    }

    private String trimForVisualSearch(String value) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ").trim();
        return normalized.length() <= 4000 ? normalized : normalized.substring(0, 4000);
    }

    private void putIfConfigured(Map<String, String> environment, String key, String value) {
        if (value != null && !value.isBlank()) environment.put(key, value.trim());
    }

    private String outboundProxyUrl() {
        for (String name : List.of("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) return value.trim();
        }
        String host = System.getProperty("https.proxyHost", System.getProperty("http.proxyHost", ""));
        String port = System.getProperty("https.proxyPort", System.getProperty("http.proxyPort", ""));
        if (host == null || host.isBlank()) return "";
        return "http://" + host.trim() + (port == null || port.isBlank() ? "" : ":" + port.trim());
    }

    public Map<String, Object> testConnection(String apiKey, String model, String effort, String providerBaseUrl)
            throws IOException, InterruptedException {
        if (!Set.of("gpt-5.4", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna").contains(model)) {
            throw new IllegalArgumentException("Codex PPT 模型不在允许列表");
        }
        if (!Set.of("low", "medium", "high", "xhigh", "max", "ultra").contains(effort)) {
            throw new IllegalArgumentException("Codex reasoning effort 不合法");
        }
        Path codex = resolve(config.getCodexCommand());
        Path workspace = Files.createTempDirectory("web-codex-test-").toAbsolutePath().normalize();
        Path home = workspace.resolve(".codex-home");
        Files.createDirectories(home);
        setOwnerOnly(workspace);
        setOwnerOnly(home);
        try {
            AuthSource auth = prepareAuth(codex, home, apiKey);
            boolean customProvider = providerBaseUrl != null && !providerBaseUrl.isBlank() && !auth.localCli();
            if (customProvider) writeCcswitchProviderConfig(home, providerBaseUrl, model, effort);
            List<String> command = execCommand(codex, workspace, model, effort, "read-only", auth.localCli(), customProvider);
            run(command, workspace, home, "Reply exactly OK. Do not use tools.\n", Duration.ofSeconds(45), (a, b) -> {}, true);
            return Map.of("message", auth.localCli() ? "已复用本机 Codex CLI 登录态" : "Codex CLI 连接成功", "configured", true,
                    "model", model, "reasoningEffort", effort, "cliVersion", "0.147.0");
        } finally {
            deleteTree(workspace);
        }
    }

    private void login(Path codex, Path codexHome, String key) throws IOException, InterruptedException {
        List<String> command = List.of(codex.toString(), "login", "--with-api-key");
        try {
            run(command, codexHome.getParent(), codexHome, key + "\n", Duration.ofSeconds(30), (a, b) -> {}, false);
        } catch (IllegalStateException exception) {
            // Never propagate a CLI login tail because a faulty CLI could echo stdin.
            throw new IllegalStateException("Codex API Key 登录失败");
        }
    }

    /** Returns only availability metadata; never returns a path or credential material. */
    public Map<String, Object> localCliStatus() {
        return Map.of("available", localCodexHome() != null, "mode", "local-cli-fallback");
    }

    private AuthSource prepareAuth(Path codex, Path temporaryHome, String apiKey) throws IOException, InterruptedException {
        if (apiKey != null && !apiKey.isBlank()) {
            login(codex, temporaryHome, apiKey);
            return new AuthSource(false);
        }
        Path localHome = localCodexHome();
        if (localHome == null) throw new IllegalStateException("未配置 Codex PPT API Key，且未检测到已登录的本机 Codex CLI");
        copyLocalCliProfile(localHome, temporaryHome);
        return new AuthSource(true);
    }

    private Path localCodexHome() {
        String configured = System.getenv("CODEX_HOME");
        Path home = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".codex") : Path.of(configured);
        Path auth = home.toAbsolutePath().normalize().resolve("auth.json");
        try {
            return Files.isRegularFile(auth) && Files.size(auth) > 2 && Files.size(auth) <= 2L * 1024 * 1024
                    ? auth.getParent() : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * CCSwitch-compatible fallback: copy auth plus only the active model-provider
     * block. This deliberately excludes MCP servers, rules, plugins and other
     * user configuration before Codex runs in the disposable CODEX_HOME.
     */
    private void copyLocalCliProfile(Path localHome, Path temporaryHome) throws IOException {
        copyOwnerReadWrite(localHome.resolve("auth.json"), temporaryHome.resolve("auth.json"));
        Path sourceConfig = localHome.resolve("config.toml");
        if (!Files.isRegularFile(sourceConfig) || Files.size(sourceConfig) > 512L * 1024) return;
        String filtered = filteredProviderConfig(Files.readAllLines(sourceConfig, StandardCharsets.UTF_8));
        if (!copyReferencedCatalog(localHome, temporaryHome, filtered)) {
            // Do not leave a dangling external catalog path in the isolated home.
            filtered = filtered.replaceAll("(?m)^\\s*model_catalog_json\\s*=.*(?:\\R|$)", "");
        }
        if (!filtered.isBlank()) Files.writeString(temporaryHome.resolve("config.toml"), filtered, StandardCharsets.UTF_8);
    }

    private String filteredProviderConfig(List<String> lines) {
        String provider = "";
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("model_provider") && trimmed.contains("=")) {
                String value = trimmed.substring(trimmed.indexOf('=') + 1).trim().replaceAll("^\"|\"$", "");
                if (value.matches("[A-Za-z0-9_.-]{1,80}")) provider = value;
            }
        }
        Set<String> topLevel = Set.of("model", "review_model", "model_provider", "model_reasoning_effort",
                "disable_response_storage", "network_access", "model_context_window", "model_auto_compact_token_limit",
                "web_search", "model_catalog_json");
        String prefix = provider.isBlank() ? "" : "[model_providers." + provider;
        boolean inActiveProvider = false;
        StringBuilder kept = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inActiveProvider = !prefix.isBlank() && (trimmed.equals(prefix + "]") || trimmed.startsWith(prefix + "."));
            }
            if (inActiveProvider) {
                kept.append(line).append('\n');
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals > 0 && !trimmed.startsWith("#") && topLevel.contains(trimmed.substring(0, equals).trim())) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString();
    }

    private boolean copyReferencedCatalog(Path localHome, Path temporaryHome, String configText) throws IOException {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^\\s*model_catalog_json\\s*=\\s*\\\"([^\\\"]+)\\\"").matcher(configText);
        if (!matcher.find()) return false;
        Path relative = Path.of(matcher.group(1)).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || relative.getNameCount() != 1) return false;
        Path source = localHome.resolve(relative).normalize();
        if (!source.startsWith(localHome) || !Files.isRegularFile(source) || Files.size(source) > 4L * 1024 * 1024) return false;
        copyOwnerReadWrite(source, temporaryHome.resolve(relative));
        return true;
    }

    private void copyOwnerReadWrite(Path source, Path target) throws IOException {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        try { Files.setPosixFilePermissions(target, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
        catch (UnsupportedOperationException ignored) {}
    }

    private void writeCcswitchProviderConfig(Path home, String baseUrl, String model, String effort) throws IOException {
        String normalized = java.net.URI.create(baseUrl).toString().replaceAll("/+$", "");
        String configText = """
                model_provider = "OpenAI"
                model = "%s"
                review_model = "%s"
                model_reasoning_effort = "%s"
                disable_response_storage = true
                network_access = "enabled"
                model_context_window = 1000000
                model_auto_compact_token_limit = 900000

                [model_providers.OpenAI]
                name = "OpenAI"
                base_url = "%s"
                wire_api = "responses"
                requires_openai_auth = true
                """.formatted(model, model, effort, normalized);
        Files.writeString(home.resolve("config.toml"), configText, StandardCharsets.UTF_8);
        try { Files.setPosixFilePermissions(home.resolve("config.toml"),
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
        catch (UnsupportedOperationException ignored) { }
    }

    private List<String> execCommand(Path codex, Path workspace, String model, String effort, String sandbox,
                                     boolean localCli, boolean customProvider) {
        List<String> command = new ArrayList<>(List.of(codex.toString(), "exec", "-", "--ephemeral"));
        // The temporary home only contains a filtered CCSwitch-compatible provider profile.
        if (!localCli && !customProvider) command.add("--ignore-user-config");
        command.addAll(List.of("--ignore-rules", "--skip-git-repo-check", "--sandbox", sandbox, "--json", "--color", "never",
                "--model", model, "--config", "model_reasoning_effort=\"" + effort + "\"", "--cd", workspace.toString()));
        return command;
    }

    private record AuthSource(boolean localCli) {}

    private void runFinalize(Path script, Path taskDir, Path vendor, String fontFamily,
                             BiConsumer<String, Map<String, Object>> events) throws IOException, InterruptedException {
        List<String> command = List.of(config.getAgentCommand(),
                "--max-old-space-size=" + config.getAgentNodeMaxOldSpaceMb(), script.toString(),
                taskDir.toString(), vendor.toString());
        Map<String, String> env = Map.of(
                "PPT_AGENT_SOFFICE", config.getSofficeCommand(),
                "PPT_AGENT_PDFTOPPM", config.getPdftoppmCommand(),
                "PPT_CODEX_MAX_FILES", Integer.toString(config.getCodexMaxProjectFiles()),
                "PPT_CODEX_MAX_BYTES", Long.toString(config.getCodexMaxProjectBytes()),
                "PPT_CODEX_REQUESTED_FONT", fontFamily == null || fontFamily.isBlank() ? "Microsoft YaHei" : fontFamily);
        // This is the fixed Node exporter, not Codex JSONL. Preserve its bounded
        // deterministic preflight message so the author can repair real layout
        // findings instead of treating the exporter as a failed Codex turn.
        run(command, script.getParent(), null, "", Duration.ofSeconds(600), events, false, env);
    }

    /**
     * The fixed exporter owns the quality gates. Feed a rejected candidate back
     * to the author in its existing isolated workspace before failing the task.
     */
    private void finalizeWithVisualRepairs(PptGenerationSession session,
                                           BiConsumer<String, Map<String, Object>> events,
                                           Path finalizeScript, Path vendor, Path workspace, Path codexHome,
                                           List<String> command, Path deck, Path target, StringBuilder log)
            throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            copyDeckToTask(deck, target);
            events.accept("rendering", Map.of("progress", attempt == 0 ? 82 : 84,
                    "message", attempt == 0 ? "正在用服务器固定导出器生成 PPTX" : "正在复核修复后的 PPTD 排版"));
            try {
                runFinalize(finalizeScript, session.getTaskDir(), vendor, session.getFontFamily(), events);
                return;
            } catch (IllegalStateException failure) {
                if (!isVisualPreflightFailure(failure) || attempt >= MAX_VISUAL_PREFLIGHT_REPAIRS) throw failure;
                events.accept("reviewing", Map.of("progress", 76,
                        "message", "发现文字排版问题，正在让 Codex 按真实检测结果修复"));
                try {
                    appendBounded(log, run(command, workspace, codexHome, repairPrompt(failure.getMessage()),
                            Duration.ofSeconds(config.getCodexTimeoutSeconds()), events, true));
                } catch (CodexProcessException repairExit) {
                    appendBounded(log, repairExit.processLog());
                    // Certain OpenAI-compatible relays have a wrapper-level non-zero
                    // repair exit even after writing the requested files. The existing
                    // deck is never trusted directly: the next loop iteration copies it
                    // into task storage and reruns the fixed exporter, text-boundary and
                    // real-render gates. If it is incomplete or still invalid, those
                    // gates either request another bounded repair or block delivery.
                    if (!Files.isDirectory(deck)) throw repairExit;
                    PptCodexRunner.log.warn("Codex repair exited {} with a retained PPTD project; validating it with the fixed exporter",
                            repairExit.exitCode());
                }
                // Do not duplicate the exporter's exact-one-manifest/path checks
                // here. A compatible relay may leave auxiliary authoring files; the
                // next iteration copies the project and the fixed exporter is the
                // sole authority that accepts or rejects it for delivery.
                if (!Files.isDirectory(deck)) throw new IllegalStateException("Codex 修复后未保留 deck 项目目录");
            }
        }
    }

    private void copyDeckToTask(Path deck, Path target) throws IOException {
        deleteTree(target);
        copyTree(deck, target, config.getCodexMaxProjectFiles(), config.getCodexMaxProjectBytes());
    }

    /**
     * Recovery remains safe because this path invokes the same authoritative
     * exporter, PPTD/path checks, ZIP checks and true-render gates as the
     * normal flow. A retained but still-invalid deck is never delivered.
     */
    private boolean finalizeRetainedDeck(PptGenerationSession session,
                                         BiConsumer<String, Map<String, Object>> events,
                                         Path finalizeScript, Path vendor, Path deck, Path target)
            throws IOException, InterruptedException {
        if (singleManifest(deck) == null) return false;
        copyDeckToTask(deck, target);
        events.accept("rendering", Map.of("progress", 84, "message", "正在复核返修后的 PPTD 排版"));
        try {
            runFinalize(finalizeScript, session.getTaskDir(), vendor, session.getFontFamily(), events);
            return true;
        } catch (IllegalStateException failure) {
            if (!isVisualPreflightFailure(failure)) throw failure;
            return false;
        }
    }

    private boolean isVisualPreflightFailure(IllegalStateException failure) {
        String message = failure.getMessage();
        return message != null && (message.contains("PPTD visual preflight failed")
                || message.contains("PPTX text-frame boundary check failed"));
    }

    private String repairPrompt(String failure) {
        return """
                Repair the existing PPTD project in ./deck only. Do not create a second deck, do not export PPTX,
                and do not use browser tools, package managers, network downloaders, scripts, or binaries.
                The server's fixed visual preflight rejected your project with the following exact findings:

                %s

                Fix every listed finding. Shorten text before reducing font size; increase text bounds for legitimate
                multi-line copy; keep every element inside the 16:9 canvas; remove raw full-page PDF screenshots and
                replace them with readable extracted figures or vector redraws. Preserve the requested visual language,
                source-grounded content, page count, and explicit requested font. Re-read the relevant .page files and
                validate the complete ./deck project before finishing.
                """.formatted(tail(failure == null ? "" : failure, 12000));
    }

    private String run(List<String> command, Path cwd, Path codexHome, String stdin, Duration timeout,
                       BiConsumer<String, Map<String, Object>> events, boolean parseJson)
            throws IOException, InterruptedException {
        return run(command, cwd, codexHome, stdin, timeout, events, parseJson, Map.of());
    }

    private String run(List<String> command, Path cwd, Path codexHome, String stdin, Duration timeout,
                       BiConsumer<String, Map<String, Object>> events, boolean parseJson, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        if (codexHome != null) builder.environment().put("CODEX_HOME", codexHome.toString());
        prependLockedCodexBin(builder);
        builder.environment().putAll(extraEnv);
        Process process = builder.start();
        active.add(process);
        try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(stdin == null ? "" : stdin);
        }
        StringBuilder outputLog = new StringBuilder();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (System.nanoTime() < deadline) {
                while (reader.ready()) appendLine(reader.readLine(), outputLog, events, parseJson);
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) break;
            }
            if (process.isAlive()) {
                terminate(process);
                throw new IllegalStateException("Codex 演示任务超时");
            }
            String line;
            while ((line = reader.readLine()) != null) appendLine(line, outputLog, events, parseJson);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                if (!parseJson) {
                    throw new IllegalStateException(tail(outputLog.toString(), 12000));
                }
                // Some OpenAI-compatible Codex relays return a non-zero wrapper exit
                // after emitting a complete JSONL turn. The protocol terminal event is
                // authoritative here: do not discard a fully written PPTD repair.
                // A terminal turn.completed is successful; turn.failed is the protocol
                // terminal failure event and never emits turn.completed.
                if (parseJson && hasCompletedTurn(outputLog)) {
                    PptCodexRunner.log.warn("Codex CLI returned exitCode={} after a completed JSONL turn; accepting the completed turn", exitCode);
                    return outputLog.toString();
                }
                throw new CodexProcessException(exitCode, outputLog.toString());
            }
            return outputLog.toString();
        } catch (InterruptedException interrupted) {
            terminate(process);
            throw interrupted;
        } finally {
            active.remove(process);
        }
    }

    /** PPT generation is single-worker, so cancelling the active task may safely terminate its child process tree. */
    public void cancelActiveProcesses() { active.forEach(this::terminate); }

    /**
     * Codex agent shell calls resolve `codex-linux-sandbox` from PATH. The
     * deployment creates that alias next to the locked CLI; inherit only that
     * directory for this child instead of relying on a system-wide install.
     */
    private void prependLockedCodexBin(ProcessBuilder builder) {
        try {
            Path bin = resolve(config.getCodexCommand()).getParent();
            if (bin == null || !Files.isDirectory(bin)) return;
            String existing = builder.environment().getOrDefault("PATH", "");
            builder.environment().put("PATH", bin + (existing.isBlank() ? "" : File.pathSeparator + existing));
        } catch (Exception ignored) {
            // The CLI executable check in run() reports a clear error if this path is unusable.
        }
    }

    private void appendLine(String line, StringBuilder log, BiConsumer<String, Map<String, Object>> events, boolean parseJson) {
        if (line == null) return;
        log.append(line).append('\n');
        int max = (int) Math.min(Integer.MAX_VALUE, Math.max(64 * 1024, config.getCodexMaxLogBytes()));
        if (log.length() > max) log.delete(0, log.length() - max);
        if (!parseJson) return;
        try {
            Map<String, Object> event = objectMapper.readValue(line, new TypeReference<>() {});
            String type = String.valueOf(event.getOrDefault("type", event.getOrDefault("event", "")));
            if (type.contains("error")) events.accept("reviewing", Map.of("progress", 70, "message", "Codex 正在检查并修复生成结果"));
            else if (type.contains("item")) events.accept("authoring", Map.of("progress", 45, "message", "Codex 正在编排演示内容"));
        } catch (Exception ignored) {}
    }

    private boolean hasCompletedTurn(StringBuilder events) {
        // JSONL is machine-generated by Codex. Check the terminal marker directly
        // first: compatible relays have occasionally added fields that Jackson's
        // map conversion does not retain consistently across wrapper versions.
        if (events.indexOf("turn.completed") >= 0) return true;
        for (String line : events.toString().split("\\R")) {
            try {
                Map<String, Object> event = objectMapper.readValue(line, new TypeReference<>() {});
                String type = String.valueOf(event.getOrDefault("type", event.getOrDefault("event", "")));
                if ("turn.completed".equals(type)) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    /**
     * A failed Codex process may include provider diagnostics in stdout. Keep that
     * material out of task metadata/SSE while retaining a short sanitized server log
     * that can distinguish an authentication failure from an authoring failure.
     */
    private void logCodexDiagnostic(PptGenerationSession session, String reason, StringBuilder events) {
        Set<String> types = new java.util.LinkedHashSet<>();
        String finalMessage = "";
        for (String line : events.toString().split("\\R")) {
            try {
                Map<String, Object> event = objectMapper.readValue(line, new TypeReference<>() {});
                String type = String.valueOf(event.getOrDefault("type", event.getOrDefault("event", "")));
                if (!type.isBlank()) types.add(type);
                Object item = event.get("item");
                if (item instanceof Map<?, ?> map && "agent_message".equals(String.valueOf(map.get("type")))) {
                    Object text = map.get("text");
                    if (text != null) finalMessage = String.valueOf(text);
                }
            } catch (Exception ignored) { }
        }
        log.warn("Codex PPT diagnostic: taskId={}, reason={}, eventTypes={}, finalMessage={}",
                session.getTaskId(), reason, types, sanitizeDiagnostic(finalMessage));
    }

    private String sanitizeDiagnostic(String value) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        normalized = normalized.replaceAll("(?i)(sk-[A-Za-z0-9_-]{6,}|bearer\\s+[^\\s]+)", "***");
        return normalized.isBlank() ? "(no agent final message)" : tail(normalized, 900);
    }

    private void appendBounded(StringBuilder target, String value) {
        if (value == null || value.isEmpty()) return;
        target.append(value);
        int max = (int) Math.min(Integer.MAX_VALUE, Math.max(64 * 1024, config.getCodexMaxLogBytes()));
        if (target.length() > max) target.delete(0, target.length() - max);
    }

    private static final class CodexProcessException extends IllegalStateException {
        private final int exitCode;
        private final String processLog;

        private CodexProcessException(int exitCode, String processLog) {
            super("Codex CLI 子进程异常退出（exit=" + exitCode + "）");
            this.exitCode = exitCode;
            this.processLog = processLog == null ? "" : processLog;
        }

        private int exitCode() { return exitCode; }
        private String processLog() { return processLog; }
    }

    private String prompt(PptGenerationSession session) {
        return """
                You are running as the authorized PPTD authoring worker for a web service.
                Read ./skill/SKILL.md and ./skill/reference/pptd.md completely, then follow their PPTD authoring rules.
                The user request is in ./input/request.txt. Any extracted source text, validated upload and extracted figures are under ./input/.
                Selected design key: %s. Requested font: %s. Research mode: %s. AI image mode: %s.
                If ./input/generated-images/ exists, its PNGs were generated by the server with a separately configured
                Images API. Read ./input/presentation-plan.json before authoring. Each GPT image has a slideId binding;
                use every GPT image exactly once and only on its bound slideId. In supplement mode, use them only for fitting
                missing visual slots after stronger uploaded or source-grounded material. In prefer mode, use them as the first visual option where semantically suitable.
                They are not factual sources: never display a source URL/citation for them and never add text to them.
                If ./input/web-images/ exists, it contains server-downloaded, validation-passed search candidates plus
                image-assets.json. Prefer topical uploaded/extracted figures and explicitly licensed assets. Search-indexed
                candidates may have rightsStatus=unverified: use them only when clearly relevant, retain their source metadata
                in page notes, and never describe unverified items as licensed. Do not display a bibliography page.
                Work only inside this disposable workspace. Create the final self-contained project at ./deck with exactly
                one ./deck/deck.pptd, ./deck/pages/*.page, and ./deck/media/ as needed. Produce 3-30 pages. %s
                Do not run export_pptx.py, browser tools, package managers, network downloaders, or create scripts/binaries.
                Do not write outside ./deck. Validate PPTD v2 structure and closed relative paths yourself.
                Every visible text element must explicitly use the requested font. Keep all text inside its own bounds:
                shorten conclusion titles before reducing their size, give multi-line titles sufficient height, and never let
                a title overlap a subtitle, diagram, image, or body copy. Do not emit LaTex/Markdown source syntax as text.
                Never use a full PDF page screenshot as a slide image; use an extracted figure with legible labels, or redraw
                the relationship as vectors. Do not include template samples, “Link Start!”, standalone “第 N 页”, “Full”,
                or visible bibliography/reference pages. Cite source material only in the page metadata/notes.
                When a custom template exists at ./input/template.pptx, use it as the visual reference as described by the Skill.
                Finish only after the complete PPTD project is present. The server will export and render it using fixed vendored code.
                """.formatted(session.getTemplateKey(), session.getFontFamily(), session.getResearchMode(), session.getImageGenerationMode(),
                requestedPageCountInstruction(session));
    }

    private String requestedPageCountInstruction(PptGenerationSession session) {
        int explicitCount = session == null ? 0 : session.getRequestedPageCount();
        if (explicitCount > 0) {
            return "The user selected " + explicitCount + " pages: create exactly " + explicitCount
                    + " pages, including cover and final page.";
        }
        String request = session == null ? "" : session.getPrompt();
        if (request == null || request.isBlank()) return "Choose an appropriate page count from the request.";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)(?<!\\d)([3-9]|[12]\\d|30)\\s*(?:页(?:PPT|演示文稿|幻灯片)?|slides?)")
                .matcher(request);
        return matcher.find() ? "The user explicitly requested " + matcher.group(1) + " pages: create exactly "
                + matcher.group(1) + " pages, including cover and final page." : "Choose an appropriate page count from the request.";
    }

    private Path singleManifest(Path deck) throws IOException {
        if (!Files.isDirectory(deck)) return null;
        try (Stream<Path> stream = Files.list(deck)) {
            List<Path> files = stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".pptd")).toList();
            return files.size() == 1 ? files.get(0) : null;
        }
    }

    private void copyIfPresent(Path source, Path target) throws IOException {
        if (source != null && Files.isRegularFile(source)) Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private String sourceSuffix(String name) {
        if (name == null) return "";
        int index = name.lastIndexOf('.');
        return index >= 0 && name.substring(index).matches("(?i)\\.(pdf|docx|pptx|xlsx|txt|md|csv|html?)") ? name.substring(index).toLowerCase() : "";
    }

    private void copyTree(Path source, Path target, int maxFiles, long maxBytes) throws IOException {
        final int[] files = {0};
        final long[] bytes = {0};
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path item : stream.toList()) {
                if (Files.isSymbolicLink(item)) throw new IllegalStateException("不允许符号链接: " + item);
                Path relative = source.relativize(item);
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target.normalize())) throw new IllegalStateException("复制路径越界");
                if (Files.isDirectory(item)) Files.createDirectories(destination);
                else if (Files.isRegularFile(item)) {
                    files[0]++;
                    bytes[0] += Files.size(item);
                    if (files[0] > maxFiles || bytes[0] > maxBytes) throw new IllegalStateException("PPTD 项目文件数量或体积超限");
                    Files.copy(item, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private Path resolve(String value) {
        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : Path.of("").toAbsolutePath().resolve(path).normalize();
    }

    private void setOwnerOnly(Path path) {
        try { Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)); }
        catch (UnsupportedOperationException | IOException ignored) {}
    }

    private void terminate(Process process) {
        process.descendants().forEach(child -> { child.destroy(); if (child.isAlive()) child.destroyForcibly(); });
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
    }

    private void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) Files.deleteIfExists(path);
        }
    }

    private String tail(String value, int limit) { return value.length() <= limit ? value : value.substring(value.length() - limit); }

    @PreDestroy
    public void shutdown() { active.forEach(this::terminate); active.clear(); }
}
