package com.web.backen.ppt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.RuntimeConfigService;
import com.web.backen.config.PptGenerationConfig;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/** Root-only PPTD generator. Codex can write only a disposable task workspace. */
@Component
public class PptCodexRunner {
    private final PptGenerationConfig config;
    private final RuntimeConfigService runtime;
    private final ObjectMapper objectMapper;
    private final Set<Process> active = ConcurrentHashMap.newKeySet();

    public PptCodexRunner(PptGenerationConfig config, RuntimeConfigService runtime, ObjectMapper objectMapper) {
        this.config = config;
        this.runtime = runtime;
        this.objectMapper = objectMapper;
    }

    public void run(PptGenerationSession session, BiConsumer<String, Map<String, Object>> events)
            throws IOException, InterruptedException {
        String apiKey = runtime.codexPptKey();
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("Codex PPT OpenAI API Key 未配置");
        Path vendor = resolve(config.getCodexVendorRoot());
        Path skill = vendor.resolve("skills/open-kimi-ppt");
        Path codex = resolve(config.getCodexCommand());
        Path finalizeScript = resolve(config.getCodexFinalizeScript());
        if (!Files.isRegularFile(codex) || !Files.isExecutable(codex)) throw new IllegalStateException("锁定的 Codex CLI 不可用: " + codex);
        if (!Files.isRegularFile(skill.resolve("SKILL.md"))) throw new IllegalStateException("open-kimi-ppt Skill 不完整");
        if (!Files.isRegularFile(finalizeScript)) throw new IllegalStateException("PPTD 固定导出桥不存在");

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
            Path previousProject = session.getTaskDir().resolve("previous-pptd-project");
            if (Files.isDirectory(previousProject)) {
                copyTree(previousProject, input.resolve("previous-pptd-project"),
                        config.getCodexMaxProjectFiles(), config.getCodexMaxProjectBytes());
            }
            Files.writeString(input.resolve("request.txt"), session.getPrompt(), StandardCharsets.UTF_8);

            events.accept("planning", Map.of("progress", 10, "message", "正在启动隔离的 Codex PPTD Agent"));
            login(codex, codexHome, apiKey);
            String prompt = prompt(session);
            List<String> command = new ArrayList<>(List.of(
                    codex.toString(), "exec", "-", "--ephemeral", "--ignore-user-config", "--ignore-rules",
                    "--skip-git-repo-check", "--sandbox", "workspace-write", "--json", "--color", "never",
                    "--model", runtime.codexPptModel(), "--config",
                    "model_reasoning_effort=\"" + runtime.codexPptReasoningEffort() + "\"", "--cd", workspace.toString()));
            String log = run(command, workspace, codexHome, prompt, Duration.ofSeconds(config.getCodexTimeoutSeconds()), events, true);
            Files.writeString(session.getTaskDir().resolve("codex-events.jsonl"), log, StandardCharsets.UTF_8);
            Path manifest = singleManifest(deck);
            if (manifest == null) throw new IllegalStateException("Codex 未生成唯一的 deck/*.pptd");
            Path target = session.getTaskDir().resolve("pptd-project");
            deleteTree(target);
            copyTree(deck, target, config.getCodexMaxProjectFiles(), config.getCodexMaxProjectBytes());
            events.accept("rendering", Map.of("progress", 82, "message", "正在用服务器固定导出器生成 PPTX"));
            runFinalize(finalizeScript, session.getTaskDir(), vendor, events);
        } finally {
            deleteTree(workspace);
        }
    }

    public void finalizeExisting(PptGenerationSession session, BiConsumer<String, Map<String, Object>> events)
            throws IOException, InterruptedException {
        Path vendor = resolve(config.getCodexVendorRoot());
        Path finalizeScript = resolve(config.getCodexFinalizeScript());
        runFinalize(finalizeScript, session.getTaskDir(), vendor, events);
    }

    public Map<String, Object> testConnection(String apiKey, String model, String effort)
            throws IOException, InterruptedException {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("Codex PPT API Key 未配置");
        if (!Set.of("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna").contains(model)) {
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
            login(codex, home, apiKey);
            List<String> command = List.of(codex.toString(), "exec", "-", "--ephemeral", "--ignore-user-config",
                    "--ignore-rules", "--skip-git-repo-check", "--sandbox", "read-only", "--json", "--color", "never",
                    "--model", model, "--config", "model_reasoning_effort=\"" + effort + "\"", "--cd", workspace.toString());
            run(command, workspace, home, "Reply exactly OK. Do not use tools.\n", Duration.ofSeconds(45), (a, b) -> {}, true);
            return Map.of("message", "Codex CLI 连接成功", "configured", true,
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

    private void runFinalize(Path script, Path taskDir, Path vendor,
                             BiConsumer<String, Map<String, Object>> events) throws IOException, InterruptedException {
        List<String> command = List.of(config.getAgentCommand(),
                "--max-old-space-size=" + config.getAgentNodeMaxOldSpaceMb(), script.toString(),
                taskDir.toString(), vendor.toString());
        Map<String, String> env = Map.of(
                "PPT_AGENT_SOFFICE", config.getSofficeCommand(),
                "PPT_AGENT_PDFTOPPM", config.getPdftoppmCommand(),
                "PPT_CODEX_MAX_FILES", Integer.toString(config.getCodexMaxProjectFiles()),
                "PPT_CODEX_MAX_BYTES", Long.toString(config.getCodexMaxProjectBytes()));
        run(command, script.getParent(), null, "", Duration.ofSeconds(600), events, true, env);
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
        builder.environment().putAll(extraEnv);
        Process process = builder.start();
        active.add(process);
        try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(stdin == null ? "" : stdin);
        }
        StringBuilder log = new StringBuilder();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (System.nanoTime() < deadline) {
                while (reader.ready()) appendLine(reader.readLine(), log, events, parseJson);
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) break;
            }
            if (process.isAlive()) {
                terminate(process);
                throw new IllegalStateException("Codex PPT 任务超时");
            }
            String line;
            while ((line = reader.readLine()) != null) appendLine(line, log, events, parseJson);
            if (process.exitValue() != 0) throw new IllegalStateException("Codex PPT 子进程失败: " + tail(log.toString(), 4000));
            return log.toString();
        } finally {
            active.remove(process);
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
            else if (type.contains("item")) events.accept("authoring", Map.of("progress", 45, "message", "Codex 正在生成 PPTD 页面"));
        } catch (Exception ignored) {}
    }

    private String prompt(PptGenerationSession session) {
        return """
                You are running as the root-only PPTD authoring worker for a web service.
                Read ./skill/SKILL.md and ./skill/reference/pptd.md completely, then follow their PPTD authoring rules.
                The user request is in ./input/request.txt. Any extracted source text, validated upload and extracted figures are under ./input/.
                Selected design key: %s. Requested font: %s. Research mode: %s.
                Work only inside this disposable workspace. Create the final self-contained project at ./deck with exactly
                one ./deck/deck.pptd, ./deck/pages/*.page, and ./deck/media/ as needed. Produce 3-30 pages.
                Do not run export_pptx.py, browser tools, package managers, network downloaders, or create scripts/binaries.
                Do not write outside ./deck. Validate PPTD v2 structure and closed relative paths yourself.
                When a custom template exists at ./input/template.pptx, use it as the visual reference as described by the Skill.
                Finish only after the complete PPTD project is present. The server will export and render it using fixed vendored code.
                """.formatted(session.getTemplateKey(), session.getFontFamily(), session.getResearchMode());
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
