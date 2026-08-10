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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Runs the provider-neutral presentation Agent as an isolated Node process. */
@Component
public class PptAgentRunner {
    private final PptGenerationConfig config;
    private final RuntimeConfigService runtime;
    private final ObjectMapper objectMapper;
    private final Set<Process> active = ConcurrentHashMap.newKeySet();

    public PptAgentRunner(PptGenerationConfig config, RuntimeConfigService runtime, ObjectMapper objectMapper) {
        this.config = config;
        this.runtime = runtime;
        this.objectMapper = objectMapper;
    }

    public void run(PptGenerationSession session, Path storageRoot,
                    BiConsumer<String, Map<String, Object>> eventConsumer) throws IOException, InterruptedException {
        Path taskDir = session.getTaskDir().toAbsolutePath().normalize();
        Path projectRoot = Path.of(config.getAgentProjectRoot()).toAbsolutePath().normalize();
        Path script = resolveFromWorkingDirectory(config.getAgentScript());
        Path jobFile = taskDir.resolve("agent-job.json");
        Path previousPlan = taskDir.resolve("previous-agent-plan.json");
        Path previousSources = taskDir.resolve("previous-sources.json");
        Path previousOutput = taskDir.resolve(
                "html".equalsIgnoreCase(session.getOutputFormat()) ? "previous-output.html" : "previous-output.pptx");
        Path previousPreview = taskDir.resolve("previous-preview");

        if (!Files.isRegularFile(script)) throw new IllegalStateException("PPT Agent worker 不存在: " + script);
        if (!Files.isRegularFile(session.getTemplatePath()) && "pptx".equalsIgnoreCase(session.getOutputFormat())) {
            throw new IllegalStateException("PPTX Agent 缺少已校验的源模板");
        }

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("taskId", session.getTaskId());
        job.put("prompt", session.getPrompt());
        job.put("templateKey", session.getTemplateKey());
        job.put("outputFormat", session.getOutputFormat());
        job.put("researchMode", session.getResearchMode());
        job.put("visualMode", session.getVisualMode());
        job.put("fontFamily", session.getFontFamily());
        job.put("maxSources", config.getAgentMaxSources());
        job.put("maxSearches", runtime.tavilyMaxSearches());
        job.put("templateFile", session.getTemplatePath().toAbsolutePath().normalize().toString());
        job.put("sourceFile", Files.isRegularFile(session.getPaperPath())
                ? session.getPaperPath().toAbsolutePath().normalize().toString() : "");
        Path extractedText = taskDir.resolve("source.txt");
        job.put("sourceTextFile", Files.isRegularFile(extractedText) ? extractedText.toString() : "");
        job.put("sourceFileName", session.getPaperFileName() == null ? "" : session.getPaperFileName());
        List<Map<String, String>> sourceImages = new ArrayList<>();
        if (Files.isDirectory(session.getImagesDir())) {
            try (Stream<Path> images = Files.list(session.getImagesDir())) {
                List<Path> paths = images.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().matches("(?i).+\\.(png|jpe?g|gif)$"))
                        // PDF page renders are useful to the text extractor, but are
                        // document thumbnails rather than presentation visuals. Passing
                        // them to the Agent lets a full academic-paper page be inserted
                        // into a small template image frame, which is unreadable at
                        // slide scale. Extracted figures/tables remain eligible.
                        .filter(path -> !path.getFileName().toString()
                                .toLowerCase(java.util.Locale.ROOT).startsWith("paper-page-"))
                        .filter(path -> {
                            try {
                                return Files.size(path) <= 4L * 1024 * 1024;
                            } catch (IOException ignored) {
                                return false;
                            }
                        })
                        .sorted()
                        .limit(8)
                        .toList();
                long selectedBytes = 0;
                for (int index = 0; index < paths.size(); index++) {
                    long bytes = Files.size(paths.get(index));
                    if (selectedBytes + bytes > 16L * 1024 * 1024) break;
                    selectedBytes += bytes;
                    sourceImages.add(Map.of(
                            "id", "I" + String.format("%02d", index + 1),
                            "fileName", paths.get(index).getFileName().toString(),
                            "path", paths.get(index).toAbsolutePath().normalize().toString()));
                }
            }
        }
        job.put("sourceImages", sourceImages);
        job.put("projectRoot", projectRoot.toString());
        job.put("storageRoot", storageRoot.toAbsolutePath().normalize().toString());
        if (Files.isRegularFile(previousPlan)) job.put("previousPlanFile", previousPlan.toString());
        if (Files.isRegularFile(previousSources)) job.put("previousSourcesFile", previousSources.toString());
        if (Files.isRegularFile(previousOutput)) job.put("previousOutputFile", previousOutput.toString());
        if (Files.isDirectory(previousPreview)) {
            try (Stream<Path> previews = Files.list(previousPreview)) {
                job.put("previousPreviewFiles", previews.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().matches("slide-\\d+\\.png"))
                        .sorted(Comparator.comparingInt(path -> Integer.parseInt(
                                path.getFileName().toString().replaceAll("\\D+", ""))))
                        .limit(30)
                        .map(path -> path.toAbsolutePath().normalize().toString())
                        .toList());
            }
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jobFile.toFile(), job);

        List<String> command = new ArrayList<>();
        command.add(config.getAgentCommand());
        command.add("--max-old-space-size=" + config.getAgentNodeMaxOldSpaceMb());
        command.add(script.toString());
        command.add(jobFile.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(script.getParent().getParent().getParent().toFile());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("PPT_AGENT_LLM_ENDPOINT", runtime.llmEndpoint());
        env.put("PPT_AGENT_LLM_KEY", runtime.llmKey());
        env.put("PPT_AGENT_LLM_MODEL", runtime.llmModel());
        env.put("PPT_AGENT_VISION_MODEL", config.getVisionModel());
        env.put("PPT_AGENT_LLM_PROTOCOL", runtime.resolvedLlmProtocol());
        env.put("PPT_AGENT_TAVILY_ENDPOINT", runtime.tavilyUrl());
        env.put("PPT_AGENT_TAVILY_KEY", runtime.tavilyKey());
        env.put("PPT_AGENT_SEMANTIC_SCHOLAR_KEY", runtime.semanticScholarKey());
        putIfConfigured(env, "PPT_AGENT_MIMO_SEARCH_ENDPOINT", runtime.mimoSearchEndpoint());
        putIfConfigured(env, "PPT_AGENT_MIMO_SEARCH_KEY", runtime.mimoSearchKey());
        putIfConfigured(env, "PPT_AGENT_MIMO_SEARCH_MODEL", runtime.mimoSearchModel());
        putIfConfigured(env, "PPT_AGENT_SOFFICE", config.getSofficeCommand());
        putIfConfigured(env, "PPT_AGENT_PDFTOPPM", config.getPdftoppmCommand());
        putIfConfigured(env, "PPT_AGENT_CHROME", config.getChromeCommand());
        putIfConfigured(env, "PPT_AGENT_PROXY_URL", agentProxyUrl());

        Process process = builder.start();
        active.add(process);
        StringBuilder diagnostics = new StringBuilder();
        long deadline = System.nanoTime() + Duration.ofSeconds(config.getAgentTimeoutSeconds()).toNanos();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (System.nanoTime() < deadline) {
                while (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) break;
                    diagnostics.append(line).append('\n');
                    if (diagnostics.length() > 64 * 1024) diagnostics.delete(0, diagnostics.length() - 64 * 1024);
                    try {
                        Map<String, Object> event = objectMapper.readValue(line, new TypeReference<>() {});
                        String name = String.valueOf(event.remove("event"));
                        eventConsumer.accept(name, event);
                    } catch (Exception ignored) {
                        // Non-JSON library diagnostics stay in the bounded task log.
                    }
                }
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) break;
            }
            if (process.isAlive()) {
                terminate(process);
                throw new IllegalStateException("PPT Agent 超过 " + config.getAgentTimeoutSeconds() + " 秒");
            }
            while (reader.ready()) {
                String line = reader.readLine();
                if (line == null) break;
                diagnostics.append(line).append('\n');
                try {
                    Map<String, Object> event = objectMapper.readValue(line, new TypeReference<>() {});
                    String name = String.valueOf(event.remove("event"));
                    eventConsumer.accept(name, event);
                } catch (Exception ignored) {}
            }
            Files.writeString(taskDir.resolve("agent.log"), trim(diagnostics.toString()), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IllegalStateException("PPT Agent 执行失败: " + trim(diagnostics.toString()));
        } finally {
            active.remove(process);
        }
    }

    private Path resolveFromWorkingDirectory(String value) {
        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : Path.of("").toAbsolutePath().resolve(path).normalize();
    }

    private void putIfConfigured(Map<String, String> environment, String key, String value) {
        if (value != null && !value.isBlank()) environment.put(key, value.trim());
        else environment.remove(key);
    }

    private String agentProxyUrl() {
        for (String name : List.of("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) return value.trim();
        }
        String host = System.getProperty("https.proxyHost", System.getProperty("http.proxyHost", ""));
        String port = System.getProperty("https.proxyPort", System.getProperty("http.proxyPort", ""));
        if (host == null || host.isBlank()) return "";
        return "http://" + host.trim() + (port == null || port.isBlank() ? "" : ":" + port.trim());
    }

    private void terminate(Process process) {
        process.descendants().forEach(child -> {
            child.destroy();
            if (child.isAlive()) child.destroyForcibly();
        });
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
    }

    private String trim(String value) {
        if (value == null) return "";
        String clean = value.strip();
        return clean.length() <= 8000 ? clean : clean.substring(clean.length() - 8000);
    }

    @PreDestroy
    public void shutdown() {
        active.forEach(this::terminate);
        active.clear();
    }
}
