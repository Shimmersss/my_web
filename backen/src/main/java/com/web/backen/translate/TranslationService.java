package com.web.backen.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.config.TranslationConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);
    private final PdfParseService pdfParseService;
    private final BabelDocService babelDocService;
    private final TranslationConfig config;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, TranslationSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private Path storageDir;

    public TranslationService(PdfParseService pdfParseService, BabelDocService babelDocService,
                              TranslationConfig config, ObjectMapper objectMapper) {
        this.pdfParseService = pdfParseService;
        this.babelDocService = babelDocService;
        this.config = config;
        this.objectMapper = objectMapper;
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, config.getQueueCapacity())),
                runnable -> {
                    Thread thread = new Thread(runnable, "translation-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PostConstruct
    public void initialize() throws IOException {
        storageDir = Path.of(config.getStorageDir()).toAbsolutePath().normalize();
        Files.createDirectories(storageDir);
        List<TranslationSession> recoveredSessions = loadRecentSessions();
        cleanupHistory();
        resumeIncompleteSessions(recoveredSessions);
        log.info("翻译队列已启动: workers=1, queueCapacity={}, maxHistory={}, storage={}",
                config.getQueueCapacity(), config.getMaxHistory(), storageDir);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public TranslationSession createSessionPreview(String fileName, InputStream pdfStream) throws Exception {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        Path taskDir = Files.createDirectories(storageDir.resolve(taskId));
        Path inputPdf = taskDir.resolve("input.pdf");
        try (InputStream input = pdfStream) {
            Files.copy(input, inputPdf, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            int totalPages = pdfParseService.getTotalPages(inputPdf);
            TranslationSession session = new TranslationSession(taskId, fileName, taskDir);
            session.setTotalPages(totalPages);
            session.setPageRange(1, totalPages);
            PdfParseService.PdfTextQuality textQuality = pdfParseService.analyzeTextQuality(inputPdf);
            if (textQuality != null && textQuality.suspicious()) {
                session.setTextQualitySuspicious(true);
                session.setTextQualityWarning(textQuality.warning());
            }
            sessions.put(taskId, session);
            saveMetadata(session);
            cleanupHistory();
            log.info("创建翻译预览: taskId={}, file={}, totalPages={}", taskId, fileName, totalPages);
            return session;
        } catch (Exception e) {
            deleteRecursively(taskDir);
            throw e;
        }
    }

    public TranslationSession startTranslation(String taskId, int startPage, int endPage, String fontFamily,
                                               int qps) {
        TranslationSession session = requireSession(taskId);
        synchronized (session) {
            if (Set.of("queued", "translating", "completed").contains(session.getStatus())) {
                return session;
            }
            int effectiveStart = Math.max(1, startPage);
            int effectiveEnd = Math.min(endPage, session.getTotalPages());
            if (effectiveStart > effectiveEnd) {
                throw new IllegalArgumentException("页面范围无效");
            }

            session.setPageRange(effectiveStart, effectiveEnd);
            session.setFontFamily(validateFontFamily(fontFamily));
            session.setQps(validateQps(qps));
            session.setRequestedQps(session.getQps());
            session.setResourceDowngraded(false);
            session.setResourceDowngradeReason(null);
            session.setResourceDowngradeCount(0);
            session.setErrorMessage(null);
            session.setProgress(0);
            session.setProgressStage("queued");
            session.setStatus("queued");
            saveMetadata(session);
        }

        try {
            executor.execute(() -> runTranslation(session));
        } catch (RejectedExecutionException e) {
            session.setStatus("preview");
            session.setProgressStage("");
            saveMetadata(session);
            throw new IllegalStateException("翻译队列已满，请等待前面的任务完成后再提交");
        }

        updateQueuePositions();
        emit(session, "queued", Map.of(
                "message", "任务已进入后台队列",
                "queuePosition", session.getQueuePosition()));
        log.info("提交 BabelDOC 翻译队列: taskId={}, pages={}-{}, fontFamily={}, qps={}, queuePosition={}",
                taskId, effectiveStart(session), effectiveEnd(session), session.getFontFamily(),
                session.getQps(), session.getQueuePosition());
        return session;
    }

    public TranslationSession getSession(String taskId) {
        updateQueuePositions();
        return sessions.get(taskId);
    }

    public List<TranslationSession> getRecentSessions() {
        updateQueuePositions();
        return sessions.values().stream()
                .filter(session -> !"preview".equals(session.getStatus()))
                .sorted(Comparator.comparingLong(TranslationSession::getCreatedAt).reversed())
                .limit(Math.max(1, config.getMaxHistory()))
                .toList();
    }

    public void subscribe(String taskId, SseEmitter emitter) {
        TranslationSession session = sessions.get(taskId);
        if (session == null) {
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

    public String buildDownloadContent(String taskId) {
        TranslationSession session = requireCompletedSession(taskId);
        Path translatedPdf = session.getTranslatedPdfPath();
        if (!Files.isRegularFile(translatedPdf)) {
            throw new IllegalStateException("BabelDOC 翻译 PDF 尚未生成");
        }
        try (var document = Loader.loadPDF(translatedPdf.toFile())) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new IllegalStateException("提取中文 TXT 失败: " + e.getMessage(), e);
        }
    }

    public Path getTranslatedPdf(String taskId, String mode) {
        TranslationSession session = requireCompletedSession(taskId);
        if (!Set.of("translated", "bilingual").contains(mode)) {
            throw new IllegalArgumentException("不支持的 PDF 模式: " + mode);
        }
        Path pdf = "bilingual".equals(mode) ? session.getBilingualPdfPath() : session.getTranslatedPdfPath();
        if (!Files.isRegularFile(pdf)) {
            throw new IllegalStateException("BabelDOC 翻译 PDF 尚未生成");
        }
        return pdf;
    }

    public String buildTextDownloadFileName(String taskId) {
        TranslationSession session = requireCompletedSession(taskId);
        return safeBaseName(session.getFileName()) + "-翻译结果.txt";
    }

    public String buildPdfDownloadFileName(String taskId, String mode) {
        TranslationSession session = requireCompletedSession(taskId);
        String suffix = "bilingual".equals(mode) ? "-双语对照版.pdf" : "-翻译版.pdf";
        return safeBaseName(session.getFileName()) + suffix;
    }

    private void runTranslation(TranslationSession session) {
        session.setStatus("translating");
        session.setProgressStage("starting");
        session.setQueuePosition(0);
        session.setCompletedAt(0);
        saveMetadata(session);
        updateQueuePositions();
        emit(session, "layout", Map.of("message", "正在使用 BabelDOC 分析版面、翻译并重建 PDF"));

        while (true) {
            try {
                babelDocService.translatePdf(
                        session.getInputPdfPath(), session.getTaskDir(), session.getFileName(),
                        session.getStartPage(), session.getEndPage(), session.getFontFamily(), session.getQps(),
                        progress -> sendProgress(session, progress));
                session.setProgress(100);
                session.setProgressStage("completed");
                session.setCompletedAt(System.currentTimeMillis());
                session.setStatus("completed");
                saveMetadata(session);
                emit(session, "done", Map.of("taskId", session.getTaskId()));
                completeEmitters(session.getTaskId());
                break;
            } catch (BabelDocService.ResourcePressureException e) {
                if (downgradeForResourcePressure(session, e)) {
                    continue;
                }
                failTranslation(session, e);
                break;
            } catch (Exception e) {
                failTranslation(session, e);
                break;
            } finally {
                cleanupHistory();
                updateQueuePositions();
            }
        }
    }

    private boolean downgradeForResourcePressure(TranslationSession session, BabelDocService.ResourcePressureException e) {
        int stableQps = stableQps();
        if (session.getQps() <= stableQps || session.getResourceDowngradeCount() > 0) {
            return false;
        }

        log.warn("翻译任务触发资源保护，自动降级重试: taskId={}, qps={} -> {}, reason={}",
                session.getTaskId(), session.getQps(), stableQps, e.getMessage());
        session.setResourceDowngraded(true);
        session.setResourceDowngradeReason(e.getMessage());
        session.setResourceDowngradeCount(session.getResourceDowngradeCount() + 1);
        session.setQps(stableQps);
        session.setProgress(0);
        session.setProgressStage("resource-downgrade");
        session.setStatus("translating");
        saveMetadata(session);
        emit(session, "progress", Map.of(
                "progress", 0,
                "stage", "resource-downgrade",
                "stageLabel", stageLabel("resource-downgrade"),
                "current", 0,
                "total", 0,
                "qps", session.getQps(),
                "resourceDowngraded", true,
                "resourceDowngradeReason", session.getResourceDowngradeReason()));
        return true;
    }

    private void failTranslation(TranslationSession session, Exception e) {
        log.error("翻译任务失败: taskId={}", session.getTaskId(), e);
        session.setStatus("error");
        session.setErrorMessage(e.getMessage());
        session.setProgressStage("error");
        saveMetadata(session);
        emit(session, "task-error", Map.of(
                "message", e.getMessage() != null ? e.getMessage() : "翻译过程中发生未知错误"));
        completeEmitters(session.getTaskId());
    }

    private String safeBaseName(String fileName) {
        String name = fileName == null ? "" : fileName;
        name = name.replaceAll("[\\\\/\\r\\n\\t\\p{Cntrl}\"]", "_").trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            name = name.substring(0, name.length() - 4).trim();
        }
        name = name.replaceAll("^[. ]+|[. ]+$", "");
        if (name.isBlank()) {
            name = "翻译结果";
        }
        return name.length() > 120 ? name.substring(0, 120).trim() : name;
    }

    private void sendProgress(TranslationSession session, BabelDocService.ProgressUpdate progress) {
        if (Double.compare(session.getProgress(), progress.progress()) == 0
                && Objects.equals(session.getProgressStage(), progress.stage())) {
            return;
        }
        session.setProgress(progress.progress());
        session.setProgressStage(progress.stage());
        emit(session, "progress", Map.of(
                "progress", progress.progress(),
                "stage", progress.stage(),
                "stageLabel", stageLabel(progress.stage()),
                "current", progress.current(),
                "total", progress.total(),
                "qps", session.getQps(),
                "resourceDowngraded", session.isResourceDowngraded(),
                "resourceDowngradeReason", session.getResourceDowngradeReason() == null
                        ? "" : session.getResourceDowngradeReason()));
    }

    private void sendSnapshot(TranslationSession session, SseEmitter emitter) {
        String status = session.getStatus();
        if ("completed".equals(status)) {
            sendAndComplete(emitter, "done", Map.of("taskId", session.getTaskId()));
        } else if ("error".equals(status)) {
            sendAndComplete(emitter, "task-error", Map.of("message",
                    session.getErrorMessage() == null ? "翻译失败" : session.getErrorMessage()));
        } else if ("queued".equals(status)) {
            send(emitter, "queued", Map.of(
                    "message", "任务正在等待后台翻译",
                    "queuePosition", session.getQueuePosition()));
        } else {
            send(emitter, "progress", Map.of(
                    "progress", session.getProgress(),
                    "stage", session.getProgressStage(),
                    "stageLabel", stageLabel(session.getProgressStage()),
                    "current", 0,
                    "total", 0,
                    "qps", session.getQps(),
                    "resourceDowngraded", session.isResourceDowngraded(),
                    "resourceDowngradeReason", session.getResourceDowngradeReason() == null
                            ? "" : session.getResourceDowngradeReason()));
        }
    }

    private void emit(TranslationSession session, String eventName, Object data) {
        List<SseEmitter> taskEmitters = emitters.get(session.getTaskId());
        if (taskEmitters == null) return;
        taskEmitters.forEach(emitter -> {
            if (!send(emitter, eventName, data)) {
                removeEmitter(session.getTaskId(), emitter);
            }
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
        List<TranslationSession> queued = sessions.values().stream()
                .filter(session -> "queued".equals(session.getStatus()))
                .sorted(Comparator.comparingLong(TranslationSession::getCreatedAt))
                .toList();
        for (int index = 0; index < queued.size(); index++) {
            queued.get(index).setQueuePosition(index + 1);
        }
    }

    private List<TranslationSession> loadRecentSessions() {
        List<TranslationSession> incompleteSessions = new ArrayList<>();
        try (Stream<Path> taskDirs = Files.list(storageDir)) {
            taskDirs.filter(Files::isDirectory).forEach(taskDir -> {
                Path metadata = taskDir.resolve("task.json");
                if (!Files.isRegularFile(metadata)) return;
                try {
                    TranslationSession session = objectMapper.readValue(metadata.toFile(), TranslationSession.class);
                    session.setTaskDir(taskDir);
                    if (Set.of("queued", "translating").contains(session.getStatus())) {
                        incompleteSessions.add(session);
                    }
                    sessions.put(session.getTaskId(), session);
                } catch (Exception e) {
                    log.warn("读取翻译任务记录失败: {}", metadata, e);
                }
            });
        } catch (IOException e) {
            log.warn("读取翻译任务目录失败: {}", storageDir, e);
        }
        return incompleteSessions;
    }

    private void resumeIncompleteSessions(List<TranslationSession> incompleteSessions) {
        incompleteSessions.stream()
                .sorted(Comparator.comparingLong(TranslationSession::getCreatedAt))
                .forEach(session -> {
                    if (!Files.isRegularFile(session.getInputPdfPath())) {
                        session.setStatus("error");
                        session.setProgressStage("error");
                        session.setErrorMessage("后端重启后未找到原始 PDF，请重新上传");
                        saveMetadata(session);
                        return;
                    }

                    session.setStatus("queued");
                    session.setProgress(0);
                    session.setProgressStage("queued");
                    session.setErrorMessage(null);
                    session.setCompletedAt(0);
                    saveMetadata(session);
                    try {
                        executor.execute(() -> runTranslation(session));
                        log.info("恢复未完成翻译任务: taskId={}, file={}", session.getTaskId(), session.getFileName());
                    } catch (RejectedExecutionException e) {
                        session.setStatus("error");
                        session.setProgressStage("error");
                        session.setErrorMessage("后端重启后翻译队列已满，请重新提交");
                        saveMetadata(session);
                    }
                });
        updateQueuePositions();
    }

    private void saveMetadata(TranslationSession session) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(session.getMetadataPath().toFile(), session);
        } catch (IOException e) {
            log.warn("保存翻译任务记录失败: taskId={}", session.getTaskId(), e);
        }
    }

    private void cleanupHistory() {
        int keep = Math.max(1, config.getMaxHistory());
        List<TranslationSession> previews = sessions.values().stream()
                .filter(session -> "preview".equals(session.getStatus()))
                .sorted(Comparator.comparingLong(TranslationSession::getCreatedAt).reversed())
                .toList();
        cleanupSessionsAfter(previews, keep);

        List<TranslationSession> terminal = sessions.values().stream()
                .filter(session -> Set.of("completed", "error").contains(session.getStatus()))
                .sorted(Comparator.comparingLong(TranslationSession::getCreatedAt).reversed())
                .toList();
        cleanupSessionsAfter(terminal, keep);
    }

    private void cleanupSessionsAfter(List<TranslationSession> orderedSessions, int keep) {
        for (int index = keep; index < orderedSessions.size(); index++) {
            TranslationSession session = orderedSessions.get(index);
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
                    log.warn("清理翻译任务文件失败: {}", item, e);
                }
            });
        } catch (IOException e) {
            log.warn("清理翻译任务目录失败: {}", path, e);
        }
    }

    private TranslationSession requireSession(String taskId) {
        TranslationSession session = sessions.get(taskId);
        if (session == null) throw new IllegalArgumentException("任务不存在");
        return session;
    }

    private TranslationSession requireCompletedSession(String taskId) {
        TranslationSession session = requireSession(taskId);
        if (!"completed".equals(session.getStatus())) {
            throw new IllegalStateException("翻译尚未完成");
        }
        return session;
    }

    private String validateFontFamily(String fontFamily) {
        String value = fontFamily == null || fontFamily.isBlank() ? "auto" : fontFamily;
        if (!Set.of("auto", "serif", "sans-serif", "script").contains(value)) {
            throw new IllegalArgumentException("不支持的字体族: " + value);
        }
        return value;
    }

    private int validateQps(int qps) {
        int maxQps = Math.max(1, config.getMaxQps());
        if (qps < 1 || qps > maxQps) {
            throw new IllegalArgumentException("2 核 / 4 GB 服务器并发数必须在 1-" + maxQps + " 之间");
        }
        return qps;
    }

    private int stableQps() {
        int maxQps = Math.max(1, config.getMaxQps());
        return Math.max(1, Math.min(config.getStableQps(), maxQps));
    }

    private int effectiveStart(TranslationSession session) { return session.getStartPage(); }
    private int effectiveEnd(TranslationSession session) { return session.getEndPage(); }

    public String stageLabel(String stage) {
        return switch (stage) {
            case "queued" -> "等待后台翻译";
            case "starting" -> "正在启动 BabelDOC";
            case "resource-downgrade" -> "内存压力较高，已切换稳定模式重试";
            case "Parse PDF and Create Intermediate Representation" -> "解析 PDF";
            case "DetectScannedFile" -> "检测 PDF 类型";
            case "Parse Page Layout" -> "分析页面版式";
            case "Parse Tables" -> "识别表格";
            case "Parse Paragraphs" -> "识别段落";
            case "Parse Formulas and Styles" -> "识别公式与样式";
            case "Translate Paragraphs" -> "翻译正文";
            case "Typesetting" -> "重新排版";
            case "Add Fonts" -> "映射字体";
            case "Generate drawing instructions" -> "生成绘制指令";
            case "Subset font" -> "嵌入字体";
            case "Save PDF" -> "保存 PDF";
            case "completed" -> "翻译完成";
            case "error" -> "翻译失败";
            default -> stage == null || stage.isBlank() ? "处理中" : stage;
        };
    }
}
