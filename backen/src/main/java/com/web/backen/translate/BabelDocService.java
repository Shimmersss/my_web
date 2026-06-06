package com.web.backen.translate;

import com.web.backen.config.BabelDocConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Service
public class BabelDocService {

    private static final Logger log = LoggerFactory.getLogger(BabelDocService.class);
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;
    private static final long MONITOR_INTERVAL_MILLIS = 2000;

    private final BabelDocConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BabelDocService(BabelDocConfig config) {
        this.config = config;
    }

    public TranslationResult translatePdf(Path inputPdf, Path resultDir, String fileName, int startPage, int endPage,
                                          String fontFamily, int qps, Consumer<ProgressUpdate> progressConsumer) {
        if (!config.isEnabled()) {
            throw new IllegalStateException("BabelDOC 未启用，请设置 BABELDOC_ENABLED=true");
        }
        if (config.getOpenaiApiKey() == null || config.getOpenaiApiKey().isBlank()) {
            throw new IllegalStateException("BabelDOC API Key 未配置，请在 .env.local 中设置 BABELDOC_OPENAI_API_KEY");
        }

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("web-babeldoc-");
            Path inputFile = workDir.resolve(sanitizeFileName(fileName));
            Path outputDir = Files.createDirectories(workDir.resolve("output"));
            Files.copy(inputPdf, inputFile);

            List<String> command = buildCommand(inputFile, outputDir, startPage, endPage, fontFamily, qps);
            log.info("启动 BabelDOC: file={}, pages={}-{}, fontFamily={}, qps={}",
                    fileName, startPage, endPage, fontFamily, qps);

            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true);
            processBuilder.environment().put("BABELDOC_OPENAI_API_KEY", config.getOpenaiApiKey());
            Process process = processBuilder.start();
            StringBuilder output = new StringBuilder();
            Thread outputReader = new Thread(() -> readOutput(process, output, progressConsumer), "babeldoc-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            boolean completed = waitForCompletionOrResourceRisk(process, Duration.ofSeconds(config.getTimeoutSeconds()));
            if (!completed) {
                terminateProcessTree(process);
                throw new IllegalStateException("BabelDOC 处理超时，请缩小页面范围或调大 BABELDOC_TIMEOUT_SECONDS");
            }

            outputReader.join(TimeUnit.SECONDS.toMillis(10));
            String commandOutput = output.toString();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("BabelDOC 执行失败: " + tail(commandOutput, 1200));
            }

            Path translatedPdf = findPdf(outputDir, ".zh.mono.pdf", "纯中文");
            Path bilingualPdf = findPdf(outputDir, ".zh.dual.pdf", "双语");
            Files.createDirectories(resultDir);
            Path translatedResult = resultDir.resolve("translated.pdf");
            Path bilingualResult = resultDir.resolve("bilingual.pdf");
            Files.copy(translatedPdf, translatedResult, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(bilingualPdf, bilingualResult, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("BabelDOC 完成: file={}, mono={}, dual={}", fileName,
                    translatedPdf.getFileName(), bilingualPdf.getFileName());
            return new TranslationResult(translatedResult, bilingualResult);
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("BabelDOC 处理被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("BabelDOC 生成翻译 PDF 失败: " + e.getMessage(), e);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private List<String> buildCommand(Path inputFile, Path outputDir, int startPage, int endPage,
                                      String fontFamily, int qps) {
        List<String> command = new ArrayList<>(splitCommand(config.getCommand()));
        command.add(Path.of("scripts", "babeldoc_runner.py").toAbsolutePath().toString());
        command.add("--input");
        command.add(inputFile.toString());
        command.add("--output");
        command.add(outputDir.toString());
        command.add("--pages");
        command.add(startPage + "-" + endPage);
        command.add("--base-url");
        command.add(config.getOpenaiBaseUrl());
        command.add("--model");
        command.add(config.getOpenaiModel());
        command.add("--qps");
        command.add(String.valueOf(qps));
        command.add("--font-family");
        command.add(fontFamily);
        return command;
    }

    private List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalStateException("BABELDOC_COMMAND 未配置");
        }
        return Stream.of(command.trim().split("\\s+")).toList();
    }

    private String sanitizeFileName(String fileName) {
        String safeName = fileName == null ? "input.pdf" : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safeName.toLowerCase().endsWith(".pdf") ? safeName : safeName + ".pdf";
    }

    private void readOutput(Process process, StringBuilder output, Consumer<ProgressUpdate> progressConsumer) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendOutputTail(output, line);
                parseProgress(line, progressConsumer);
            }
        } catch (IOException e) {
            appendOutputTail(output, "无法读取 BabelDOC 输出: " + e.getMessage());
        }
    }

    private void appendOutputTail(StringBuilder output, String line) {
        output.append(line).append('\n');
        if (output.length() > MAX_OUTPUT_CHARS) {
            output.delete(0, output.length() - MAX_OUTPUT_CHARS);
        }
    }

    private void parseProgress(String line, Consumer<ProgressUpdate> progressConsumer) {
        if (progressConsumer == null || !line.startsWith("{")) return;
        try {
            JsonNode root = objectMapper.readTree(line);
            if (!"progress".equals(root.path("type").asText())) return;
            progressConsumer.accept(new ProgressUpdate(
                    root.path("overallProgress").asDouble(),
                    root.path("stage").asText(),
                    root.path("stageCurrent").asInt(),
                    root.path("stageTotal").asInt()));
        } catch (Exception ignored) {
            // BabelDOC dependencies may log non-JSON lines; keep them for error diagnostics.
        }
    }

    private Path findPdf(Path outputDir, String suffix, String label) throws IOException {
        try (Stream<Path> files = Files.walk(outputDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("BabelDOC 未生成" + label + " PDF"));
        }
    }

    public record TranslationResult(Path translatedPdf, Path bilingualPdf) {}
    public record ProgressUpdate(double progress, String stage, int current, int total) {}

    public static class ResourcePressureException extends IllegalStateException {
        public ResourcePressureException(String message) {
            super(message);
        }
    }

    private String tail(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "(没有命令输出)";
        }
        return text.length() <= maxLength ? text : text.substring(text.length() - maxLength);
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException e) {
                    log.warn("清理 BabelDOC 临时文件失败: {}", item, e);
                }
            });
        } catch (IOException e) {
            log.warn("清理 BabelDOC 临时目录失败: {}", path, e);
        }
    }

    private boolean waitForCompletionOrResourceRisk(Process process, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            long waitMillis = Math.max(1, Math.min(MONITOR_INTERVAL_MILLIS, remainingMillis));
            if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                return true;
            }

            Optional<String> risk = detectResourceRisk();
            if (risk.isPresent()) {
                terminateProcessTree(process);
                throw new ResourcePressureException("检测到服务器内存压力，已停止当前翻译进程: " + risk.get());
            }
        }
        return process.waitFor(1, TimeUnit.MILLISECONDS);
    }

    private Optional<String> detectResourceRisk() {
        long cgroupMemory = readCgroupMemoryCurrent();
        long cgroupRiskBytes = mibToBytes(config.getResourceCgroupLimitMiB());
        if (cgroupMemory > cgroupRiskBytes) {
            return Optional.of("服务内存 " + formatMiB(cgroupMemory)
                    + " MiB 超过阈值 " + config.getResourceCgroupLimitMiB() + " MiB");
        }

        MemoryInfo memoryInfo = readMemoryInfo();
        long minAvailableBytes = mibToBytes(config.getResourceMinAvailableMiB());
        if (memoryInfo.memAvailableBytes > 0 && memoryInfo.memAvailableBytes < minAvailableBytes) {
            return Optional.of("系统可用内存 " + formatMiB(memoryInfo.memAvailableBytes)
                    + " MiB 低于阈值 " + config.getResourceMinAvailableMiB() + " MiB");
        }
        long swapUsed = memoryInfo.swapTotalBytes - memoryInfo.swapFreeBytes;
        long maxSwapUsedBytes = mibToBytes(config.getResourceMaxSwapUsedMiB());
        if (memoryInfo.swapTotalBytes > 0 && swapUsed > maxSwapUsedBytes) {
            return Optional.of("Swap 已用 " + formatMiB(swapUsed)
                    + " MiB 超过阈值 " + config.getResourceMaxSwapUsedMiB() + " MiB");
        }
        return Optional.empty();
    }

    private long readCgroupMemoryCurrent() {
        for (Path path : cgroupMemoryPaths()) {
            try {
                if (Files.isRegularFile(path)) {
                    return Long.parseLong(Files.readString(path).trim());
                }
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    private List<Path> cgroupMemoryPaths() {
        List<Path> paths = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/cgroup"))) {
                String[] parts = line.split(":", 3);
                if (parts.length != 3) continue;
                if ("0".equals(parts[0])) {
                    paths.add(Path.of("/sys/fs/cgroup").resolve(parts[2].replaceFirst("^/", "")).resolve("memory.current"));
                } else if (parts[1].contains("memory")) {
                    paths.add(Path.of("/sys/fs/cgroup/memory").resolve(parts[2].replaceFirst("^/", "")).resolve("memory.usage_in_bytes"));
                }
            }
        } catch (IOException ignored) {
        }
        paths.add(Path.of("/sys/fs/cgroup/memory.current"));
        paths.add(Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"));
        return paths;
    }

    private MemoryInfo readMemoryInfo() {
        long memAvailable = -1;
        long swapTotal = 0;
        long swapFree = 0;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    memAvailable = parseMeminfoBytes(line);
                } else if (line.startsWith("SwapTotal:")) {
                    swapTotal = parseMeminfoBytes(line);
                } else if (line.startsWith("SwapFree:")) {
                    swapFree = parseMeminfoBytes(line);
                }
            }
        } catch (IOException ignored) {
        }
        return new MemoryInfo(memAvailable, swapTotal, swapFree);
    }

    private long parseMeminfoBytes(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) return -1;
        try {
            return Long.parseLong(parts[1]) * 1024;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String formatMiB(long bytes) {
        return String.valueOf(bytes / 1024 / 1024);
    }

    private long mibToBytes(int mib) {
        return Math.max(1L, mib) * 1024 * 1024;
    }

    private void terminateProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(ProcessHandle::destroy);
        handle.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private record MemoryInfo(long memAvailableBytes, long swapTotalBytes, long swapFreeBytes) {}
}
