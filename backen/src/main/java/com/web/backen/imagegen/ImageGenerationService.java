package com.web.backen.imagegen;

import com.web.backen.runtime.TaskCoordinator;
import com.web.backen.runtime.RuntimePaths;
import com.web.backen.runtime.AtomicTaskStore;

import com.web.backen.ai.OpenAiImageClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.settings.RuntimeConfigService;
import com.web.backen.config.ImageGenerationConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class ImageGenerationService {
    private final Set<String> executing = ConcurrentHashMap.newKeySet();
    private TaskCoordinator coordinator = TaskCoordinator.local();
    private AtomicTaskStore snapshots = new AtomicTaskStore(new ObjectMapper());
    private RuntimePaths runtimePaths = new RuntimePaths("");

    @org.springframework.beans.factory.annotation.Autowired
    void infrastructure(TaskCoordinator coordinator, AtomicTaskStore snapshots, RuntimePaths runtimePaths) {
        this.coordinator = coordinator; this.snapshots = snapshots; this.runtimePaths = runtimePaths;
        coordinator.register("imagegen", () -> Map.of("queued", sessions.values().stream().filter(t -> "queued".equals(t.getStatus())).count(),
                "creating", sessions.values().stream().filter(t -> "creating".equals(t.getStatus())).count(),
                "running", sessions.values().stream().filter(t -> "generating".equals(t.getStatus())).count(),
                "pendingCompensation", sessions.values().stream().filter(t -> t.isRefundPending()).count(),
                "pendingSnapshots", sessions.values().stream().filter(t -> snapshots.isDirty(t.getMetadataPath())).count(),
                "workerAvailable", !executor.isShutdown()));
    }

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);
    private static final Set<String> SIZES = Set.of("1024x1024", "1536x1024", "1024x1536");
    private static final Set<String> QUALITIES = Set.of("low", "medium", "high");
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "cancelled");

    private final ImageGenerationConfig config;
    private final OpenAiImageClient client;
    private final ObjectMapper mapper;
    private final QuotaService quota;
    private final RuntimeConfigService runtime;
    private final Map<String, ImageGenerationSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private final Map<String, Future<?>> taskFutures = new ConcurrentHashMap<>();
    private Path storage;

    public ImageGenerationService(ImageGenerationConfig config, OpenAiImageClient client, ObjectMapper mapper,
                                  QuotaService quota, RuntimeConfigService runtime) {
        this.config = config; this.client = client; this.mapper = mapper; this.quota = quota; this.runtime = runtime;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, config.getQueueCapacity())), r -> {
            Thread thread = new Thread(r, "image-generation-worker"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PostConstruct
    void initialize() throws IOException {
        storage = runtimePaths.resolve(config.getStorageDir());
        Files.createDirectories(storage);
        try (var dirs = Files.list(storage)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path metadata = dir.resolve("task.json");
                if (!Files.isRegularFile(metadata)) return;
                try {
                    ImageGenerationSession session = mapper.readValue(metadata.toFile(), ImageGenerationSession.class);
                    session.setTaskDir(dir); sessions.put(session.getTaskId(), session);
                } catch (Exception e) { throw new IllegalStateException("生图快照读取失败，原文件已保留", e); }
            });
        }
        sessions.values().stream().sorted(Comparator.comparingLong(ImageGenerationSession::getCreatedAt)).forEach(session -> {
            if (session.isRefundPending()) {
                refund(session, "生图任务恢复退款");
                try { persist(session); } catch (IOException e) { log.error("保存生图退款恢复状态失败: {}", session.getTaskId(), e); }
            }
            if ("creating".equals(session.getStatus())) {
                Long spend = session.getCreditTransactionId() == null ? quota.findSpendTransactionId(session.getTaskId()) : session.getCreditTransactionId();
                if (spend != null) session.setCreditTransactionId(spend);
                if (spend != null && quota.isRefunded(spend)) {
                    session.setCreditRefunded(true); session.setRefundPending(false); session.setStatus("failed");
                    try { persist(session); } catch (IOException e) { throw new IllegalStateException(e); }
                    return;
                }
                if (session.getCreditCost() > 0 && spend == null) {
                    fail(session, "任务恢复失败，请重新提交", new IllegalStateException("creating task has no charge transaction"));
                    return;
                }
                session.setStatus("queued"); session.setProgressStage("queued");
            }
            if ("queued".equals(session.getStatus()) || "generating".equals(session.getStatus())) {
                if (session.isRefundPending() || session.isCreditRefunded()
                        || (session.getCreditTransactionId() != null && quota.isRefunded(session.getCreditTransactionId()))) {
                    fail(session, "任务已进入补偿，请重新提交", new IllegalStateException("compensating task cannot resume"));
                    return;
                }

                session.setStatus("queued"); session.setProgressStage("queued");
            try { persist(session); taskFutures.put(session.getTaskId(), executor.submit(() -> run(session))); }
                catch (Exception e) { fail(session, "任务恢复失败，请重新提交", e); }
            }
        });
        cleanupHistory();
    }

    @PreDestroy void shutdown() { executor.shutdownNow(); com.web.backen.runtime.WorkerShutdown.await(executor); }

    public ImageGenerationSession create(String prompt, String mode, String size, String quality,
                                         String parentTaskId, MultipartFile referenceFile, AuthUser user) throws IOException {
        return create(prompt, mode, size, quality, parentTaskId, referenceFile, List.of(), user);
    }

    public ImageGenerationSession create(String prompt, String mode, String size, String quality,
                                         String parentTaskId, MultipartFile referenceFile, List<MultipartFile> referenceFiles,
                                         AuthUser user) throws IOException {
        try (var admission = coordinator.admit()) {
        String cleanPrompt = prompt == null ? "" : prompt.trim();
        if (cleanPrompt.isEmpty() || cleanPrompt.length() > 4000) throw new IllegalArgumentException("提示词需为 1–4000 个字符");
        String cleanMode = mode == null ? "GENERATE" : mode.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("GENERATE", "EDIT").contains(cleanMode)) throw new IllegalArgumentException("生图模式无效");
        String cleanSize = size == null ? "1024x1024" : size.trim();
        if (!SIZES.contains(cleanSize)) throw new IllegalArgumentException("画幅参数无效");
        String cleanQuality = quality == null ? "medium" : quality.toLowerCase(Locale.ROOT);
        if (!QUALITIES.contains(cleanQuality)) throw new IllegalArgumentException("质量参数无效");
        if (runtime.imageGenerationKey() == null || runtime.imageGenerationKey().isBlank()) throw new AuthException(503, "生图服务暂未配置");
        List<MultipartFile> uploads = new ArrayList<>();
        if (referenceFiles != null) uploads.addAll(referenceFiles.stream().filter(file -> file != null && !file.isEmpty()).toList());
        if (uploads.isEmpty() && referenceFile != null && !referenceFile.isEmpty()) uploads.add(referenceFile);
        boolean hasUpload = !uploads.isEmpty();
        boolean hasParent = parentTaskId != null && !parentTaskId.isBlank();
        if (uploads.size() > 4) throw new IllegalArgumentException("一次最多上传 4 张参考图");
        if ("EDIT".equals(cleanMode) && hasUpload == hasParent) throw new IllegalArgumentException("编辑模式请选择上传参考图或一张历史结果");
        if ("GENERATE".equals(cleanMode) && (hasUpload || hasParent)) throw new IllegalArgumentException("文生图无需参考图");

        String taskId = newTaskId();
        Path dir = storage.resolve(taskId); Files.createDirectory(dir);
        ImageGenerationSession session = new ImageGenerationSession(taskId, cleanPrompt, dir);
        session.setMode(cleanMode); session.setSize(cleanSize); session.setQuality(cleanQuality); session.setUserId(user.id());
        session.setStatus("creating"); session.setProgressStage("creating");
        try {
            if ("EDIT".equals(cleanMode)) prepareReferences(session, parentTaskId, uploads, user);
            int cost = user.isRoot() ? 0 : quota.imageCredit(cleanQuality);
            session.setCreditCost(cost); persist(session);
            if (cost > 0) {
                session.setCreditTransactionId(quota.spend(user.id(), cost, "IMAGE_GENERATION", taskId,
                        "GPT 生图（" + cleanQuality + "）"));
                persist(session);
            }
            session.setStatus("queued"); session.setProgressStage("queued"); persist(session); sessions.put(taskId, session);
            try { taskFutures.put(taskId, executor.submit(() -> run(session))); }
            catch (RejectedExecutionException e) {
                fail(session, "当前生图队列已满，请稍后再试", e);
                throw new IllegalStateException("当前生图队列已满，请稍后再试");
            }
            updateQueuePositions(); return session;
        } catch (Exception e) {
            if (!sessions.containsKey(taskId)) { sessions.put(taskId, session); fail(session, "生图任务创建失败，请稍后重试", e); }
            if (e instanceof AuthException authError && authError.getStatus() == 402
                    && session.getCreditTransactionId() == null && !session.isRefundPending()
                    && !snapshots.isDirty(session.getMetadataPath())) {
                sessions.remove(taskId); deleteDirectory(dir);
            }
            if (e instanceof IOException io) throw io;
            if (e instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("创建生图任务失败", e);
        }

        }
    }

    private void prepareReferences(ImageGenerationSession session, String parentTaskId, List<MultipartFile> files, AuthUser user) throws IOException {
        if (parentTaskId != null && !parentTaskId.isBlank()) {
            ImageGenerationSession parent = requireOwned(parentTaskId, user);
            if (!"completed".equals(parent.getStatus()) || !Files.isRegularFile(parent.getResultPath())) throw new IllegalArgumentException("参考任务尚未完成");
            session.setParentTaskId(parentTaskId); session.setReferenceContentType("image/png");
            Files.copy(parent.getResultPath(), session.getReferencePath()); return;
        }
        long totalBytes = 0;
        List<String> names = new ArrayList<>(), types = new ArrayList<>();
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            if (file.getSize() <= 0 || file.getSize() > config.getMaxReferenceBytes()) throw new IllegalArgumentException("每张参考图最大 20 MB");
            totalBytes += file.getSize();
            if (totalBytes > 40L * 1024 * 1024) throw new IllegalArgumentException("参考图总大小不能超过 40 MB");
            byte[] head;
            try (var input = file.getInputStream()) { head = input.readNBytes(16); }
            String type = isPng(head) ? "image/png" : isJpeg(head) ? "image/jpeg" : null;
            if (type == null) throw new IllegalArgumentException("参考图仅支持 PNG 或 JPEG");
            Path path = session.getTaskDir().resolve(String.format("reference-%02d%s", index + 1, "image/jpeg".equals(type) ? ".jpg" : ".png"));
            file.transferTo(path);
            validateImageDimensions(path);
            names.add(safeName(file.getOriginalFilename())); types.add(type);
        }
        session.setReferenceFileNames(names); session.setReferenceContentTypes(types);
        if (!names.isEmpty()) { session.setReferenceFileName(names.get(0)); session.setReferenceContentType(types.get(0)); }
    }

    private void run(ImageGenerationSession session) {
        executing.add(session.getTaskId());
        try {
        try {
            synchronized (session) {
                if ("cancelled".equals(session.getStatus())) return;
                session.setStatus("generating"); session.setProgressStage("generating"); persist(session); send(session);
            }
            byte[] output = "EDIT".equals(session.getMode())
                    ? client.edit(session.getPrompt(), session.getSize(), session.getQuality(), session.getReferencePaths(), session.getReferenceContentTypes())
                    : client.generate(session.getPrompt(), session.getSize(), session.getQuality());
            try (var resource = coordinator.heavy(() -> "cancelled".equals(session.getStatus()))) {
            Path temp = session.getTaskDir().resolve("output.png.tmp"); Files.write(temp, output); move(temp, session.getResultPath());
            createPreview(session.getResultPath(), session.getPreviewPath());
            synchronized (session) {
            if ("cancelled".equals(session.getStatus())) return;
            session.setStatus("completed"); session.setProgressStage("completed"); session.setCompletedAt(System.currentTimeMillis());
            persist(session); send(session);
            }
            completeEmitters(session.getTaskId()); cleanupHistory();
            }
        } catch (Exception e) { if (!"cancelled".equals(session.getStatus())) fail(session, "生图失败，请稍后重试", e); }
        finally { taskFutures.remove(session.getTaskId()); updateQueuePositions(); }

        } finally { executing.remove(session.getTaskId()); }
    }

    private void fail(ImageGenerationSession session, String safeMessage, Exception error) {
        log.error("生图任务失败: taskId={}", session.getTaskId(), error);
        session.setRefundPending(true);
        session.setStatus("failed"); session.setProgressStage("failed"); session.setErrorMessage(safeMessage); session.setCompletedAt(System.currentTimeMillis());
        refund(session, "生图任务未完成退款");
        try { persist(session); } catch (Exception persistError) { log.error("保存生图失败状态失败: {}", session.getTaskId(), persistError); }
        send(session); completeEmitters(session.getTaskId());
    }

    private void refund(ImageGenerationSession session, String reason) {
        synchronized (session) {
            session.setRefundPending(true);
            try {
                persist(session);
                if (session.getCreditTransactionId() == null && session.getCreditCost() > 0) {
                    session.setCreditTransactionId(quota.findSpendTransactionId(session.getTaskId())); persist(session);
                }
                if (session.getCreditTransactionId() != null && !session.isCreditRefunded()) {
                    quota.refund(session.getCreditTransactionId(), reason); session.setCreditRefunded(true);
                }
                session.setRefundPending(false); session.setRefundError(null); persist(session);
            } catch (Exception e) { session.setRefundPending(true); session.setRefundError("状态恢复或退款待重试"); }
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    void reconcilePendingRefunds() {
        sessions.values().forEach(session -> {
            synchronized (session) {
                if (!session.isRefundPending() && !snapshots.isDirty(session.getMetadataPath())) return;
                if (Set.of("failed", "cancelled").contains(session.getStatus())) refund(session, "生图任务恢复退款");
                else try { persist(session); } catch (Exception e) { log.warn("生图快照待重试: taskId={}", session.getTaskId()); }
            }
        });
    }

    public ImageGenerationSession get(String taskId) {
        ImageGenerationSession session = sessions.get(taskId);
        if (session == null) throw new IllegalArgumentException("任务不存在"); return session;
    }
    public ImageGenerationSession requireOwned(String taskId, AuthUser user) {
        ImageGenerationSession session = get(taskId);
        if (user == null || session.getUserId() != user.id()) throw new AuthException(403, "无权访问该任务"); return session;
    }
    /** Root may inspect every task, but only its owner may mutate or reuse it as an edit source. */
    public ImageGenerationSession requireReadable(String taskId, AuthUser user) {
        ImageGenerationSession session = get(taskId);
        if (user == null || (!user.isRoot() && session.getUserId() != user.id())) throw new AuthException(403, "无权访问该任务");
        return session;
    }
    public void cancel(String taskId, AuthUser user) {
        ImageGenerationSession session = requireOwned(taskId, user);
        synchronized (session) {
            if (!Set.of("queued", "generating").contains(session.getStatus())) throw new IllegalStateException("当前任务无法取消");
            session.setStatus("cancelled"); session.setProgressStage("cancelled"); session.setErrorMessage("已由用户取消"); session.setCompletedAt(System.currentTimeMillis());
            refund(session, "生图任务已取消退回额度");
            try { persist(session); } catch (IOException e) { throw new IllegalStateException("保存取消状态失败"); }
        }
        Future<?> future = taskFutures.remove(taskId); if (future != null) future.cancel(true);
        send(session); completeEmitters(taskId); updateQueuePositions();
    }
    public List<ImageGenerationSession> recent(AuthUser user) {
        if (user != null && user.isRoot()) {
            return sessions.values().stream().sorted(Comparator.comparingLong(ImageGenerationSession::getCreatedAt).reversed())
                    .limit(runtime.imageMaxGlobalHistory()).collect(Collectors.toList());
        }
        return sessions.values().stream().filter(s -> user != null && s.getUserId() == user.id())
                .sorted(Comparator.comparingLong(ImageGenerationSession::getCreatedAt).reversed())
                .limit(runtime.imageMaxHistory()).collect(Collectors.toList());
    }
    public long latestTaskUpdateAt() { return sessions.values().stream().mapToLong(ImageGenerationSession::getUpdatedAt).max().orElse(0L); }
    public void delete(String taskId, AuthUser user) {
        if (executing.contains(taskId)) throw new AuthException(409, "任务正在结束，请稍后删除");
        ImageGenerationSession session = requireOwned(taskId, user);
        if (!TERMINAL.contains(session.getStatus())) throw new IllegalStateException("运行中的任务不能删除");
        sessions.remove(taskId); deleteDirectory(session.getTaskDir());
    }
    public SseEmitter subscribe(String taskId, AuthUser user) {
        ImageGenerationSession session = requireReadable(taskId, user); SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(taskId, emitter)); emitter.onTimeout(() -> removeEmitter(taskId, emitter));
        try { emitter.send(SseEmitter.event().name("status").data(summary(session))); if (TERMINAL.contains(session.getStatus())) emitter.complete(); }
        catch (IOException e) { emitter.complete(); }
        return emitter;
    }
    public Map<String, Object> summary(ImageGenerationSession session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", session.getTaskId()); data.put("userId", session.getUserId()); data.put("mode", session.getMode()); data.put("prompt", session.getPrompt());
        data.put("size", session.getSize()); data.put("quality", session.getQuality()); data.put("parentTaskId", session.getParentTaskId());
        data.put("referenceCount", session.getReferenceContentTypes().size());
        data.put("status", session.getStatus()); data.put("stage", session.getProgressStage()); data.put("queuePosition", session.getQueuePosition());
        data.put("error", session.getErrorMessage()); data.put("createdAt", session.getCreatedAt()); data.put("updatedAt", session.getUpdatedAt());
        data.put("creditCost", session.getCreditCost()); data.put("creditRefunded", session.isCreditRefunded());
        data.put("resultReady", "completed".equals(session.getStatus())); return data;
    }

    public synchronized void cleanupHistory() {
        List<ImageGenerationSession> terminal = sessions.values().stream()
                .filter(s -> TERMINAL.contains(s.getStatus()) && !s.isRefundPending() && !executing.contains(s.getTaskId()) && !snapshots.isDirty(s.getMetadataPath()))
                .sorted(Comparator.comparingLong(ImageGenerationSession::getCreatedAt).reversed()).toList();
        Set<String> keep = new HashSet<>();
        Map<Long, Integer> perUser = new HashMap<>();
        for (ImageGenerationSession session : terminal) {
            if (keep.size() >= runtime.imageMaxGlobalHistory()) break;
            int count = perUser.getOrDefault(session.getUserId(), 0);
            if (count >= runtime.imageMaxHistory()) continue;
            keep.add(session.getTaskId()); perUser.put(session.getUserId(), count + 1);
        }
        terminal.stream().filter(s -> !keep.contains(s.getTaskId())).forEach(s -> { sessions.remove(s.getTaskId()); deleteDirectory(s.getTaskDir()); });
    }

    private void send(ImageGenerationSession session) {
        List<SseEmitter> list = emitters.getOrDefault(session.getTaskId(), new CopyOnWriteArrayList<>());
        list.removeIf(emitter -> { try { emitter.send(SseEmitter.event().name("status").data(summary(session))); return false; } catch (Exception e) { emitter.complete(); return true; } });
    }
    private void completeEmitters(String taskId) {
        List<SseEmitter> list = emitters.remove(taskId);
        if (list != null) list.forEach(SseEmitter::complete);
    }
    private void removeEmitter(String taskId, SseEmitter emitter) { emitters.getOrDefault(taskId, new CopyOnWriteArrayList<>()).remove(emitter); }
    private void updateQueuePositions() {
        int position = 1;
        for (ImageGenerationSession session : sessions.values().stream().filter(s -> "queued".equals(s.getStatus()))
                .sorted(Comparator.comparingLong(ImageGenerationSession::getCreatedAt)).toList()) {
            session.setQueuePosition(position++); try { persist(session); send(session); } catch (Exception e) { log.warn("更新队列位置失败", e); }
        }
    }
    private void persist(ImageGenerationSession session) throws IOException {
        snapshots.write(session.getMetadataPath(), session);
    }
    private void createPreview(Path source, Path target) throws IOException {
        validateImageDimensions(source);
        BufferedImage image = ImageIO.read(source.toFile()); if (image == null) throw new IOException("输出图片无法解析");
        double ratio = Math.min(1d, 640d / Math.max(image.getWidth(), image.getHeight()));
        BufferedImage preview = new BufferedImage(Math.max(1, (int) (image.getWidth() * ratio)), Math.max(1, (int) (image.getHeight() * ratio)), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = preview.createGraphics(); graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(image, 0, 0, preview.getWidth(), preview.getHeight(), null); graphics.dispose(); ImageIO.write(preview, "jpg", target.toFile());
    }
    private void move(Path source, Path target) throws IOException { try { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException e) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); } }
    private void validateImageDimensions(Path path) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) throw new IllegalArgumentException("图片无法解析");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("图片无法解析");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > config.getMaxReferencePixels()) throw new IllegalArgumentException("图片无效或超过 1600 万像素");
            } finally { reader.dispose(); }
        }
    }
    private String newTaskId() { String id; do { id = UUID.randomUUID().toString().replace("-", "").substring(0, 12); } while (sessions.containsKey(id) || Files.exists(storage.resolve(id))); return id; }
    private boolean isPng(byte[] b) { return b.length >= 8 && b[0] == (byte) 0x89 && b[1] == 0x50 && b[2] == 0x4e && b[3] == 0x47; }
    private boolean isJpeg(byte[] b) { return b.length >= 3 && b[0] == (byte) 0xff && b[1] == (byte) 0xd8 && b[2] == (byte) 0xff; }
    private String safeName(String name) { return name == null ? "reference" : Path.of(name).getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_"); }
    private void deleteDirectory(Path dir) { if (dir == null || !dir.startsWith(storage)) return; try (var paths = Files.walk(dir)) { paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) { log.warn("删除生图文件失败: {}", p); } }); } catch (IOException ignored) {} }
}
