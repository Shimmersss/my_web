package com.web.backen.imagegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.auth.RuntimeConfigService;
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
    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);
    private static final Set<String> SIZES = Set.of("1024x1024", "1536x1024", "1024x1536");
    private static final Set<String> QUALITIES = Set.of("low", "medium", "high");
    private static final Set<String> TERMINAL = Set.of("completed", "failed");

    private final ImageGenerationConfig config;
    private final OpenAiImageClient client;
    private final ObjectMapper mapper;
    private final QuotaService quota;
    private final RuntimeConfigService runtime;
    private final Map<String, ImageGenerationSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
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
        storage = Path.of(config.getStorageDir()).toAbsolutePath().normalize();
        Files.createDirectories(storage);
        try (var dirs = Files.list(storage)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path metadata = dir.resolve("task.json");
                if (!Files.isRegularFile(metadata)) return;
                try {
                    ImageGenerationSession session = mapper.readValue(metadata.toFile(), ImageGenerationSession.class);
                    session.setTaskDir(dir); sessions.put(session.getTaskId(), session);
                } catch (Exception e) { log.warn("忽略损坏的生图任务元数据: {}", dir.getFileName(), e); }
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
                if (session.getCreditCost() > 0 && spend == null) {
                    fail(session, "任务恢复失败，请重新提交", new IllegalStateException("creating task has no charge transaction"));
                    return;
                }
                session.setStatus("queued"); session.setProgressStage("queued");
            }
            if ("queued".equals(session.getStatus()) || "generating".equals(session.getStatus())) {
                session.setStatus("queued"); session.setProgressStage("queued");
                try { persist(session); executor.execute(() -> run(session)); }
                catch (Exception e) { fail(session, "任务恢复失败，请重新提交", e); }
            }
        });
        cleanupHistory();
    }

    @PreDestroy void shutdown() { executor.shutdownNow(); }

    public ImageGenerationSession create(String prompt, String mode, String size, String quality,
                                         String parentTaskId, MultipartFile referenceFile, AuthUser user) throws IOException {
        String cleanPrompt = prompt == null ? "" : prompt.trim();
        if (cleanPrompt.isEmpty() || cleanPrompt.length() > 4000) throw new IllegalArgumentException("提示词需为 1–4000 个字符");
        String cleanMode = mode == null ? "GENERATE" : mode.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("GENERATE", "EDIT").contains(cleanMode)) throw new IllegalArgumentException("生图模式无效");
        String cleanSize = size == null ? "1024x1024" : size.trim();
        if (!SIZES.contains(cleanSize)) throw new IllegalArgumentException("画幅参数无效");
        String cleanQuality = quality == null ? "medium" : quality.toLowerCase(Locale.ROOT);
        if (!QUALITIES.contains(cleanQuality)) throw new IllegalArgumentException("质量参数无效");
        if (runtime.imageGenerationKey() == null || runtime.imageGenerationKey().isBlank()) throw new AuthException(503, "生图服务暂未配置");
        boolean hasUpload = referenceFile != null && !referenceFile.isEmpty();
        boolean hasParent = parentTaskId != null && !parentTaskId.isBlank();
        if ("EDIT".equals(cleanMode) && hasUpload == hasParent) throw new IllegalArgumentException("编辑模式请选择一张参考图");
        if ("GENERATE".equals(cleanMode) && (hasUpload || hasParent)) throw new IllegalArgumentException("文生图无需参考图");

        String taskId = newTaskId();
        Path dir = storage.resolve(taskId); Files.createDirectory(dir);
        ImageGenerationSession session = new ImageGenerationSession(taskId, cleanPrompt, dir);
        session.setMode(cleanMode); session.setSize(cleanSize); session.setQuality(cleanQuality); session.setUserId(user.id());
        session.setStatus("creating"); session.setProgressStage("creating");
        try {
            if ("EDIT".equals(cleanMode)) prepareReference(session, parentTaskId, referenceFile, user);
            int cost = user.isRoot() ? 0 : quota.imageCredit(cleanQuality);
            session.setCreditCost(cost); persist(session);
            if (cost > 0) {
                session.setCreditTransactionId(quota.spend(user.id(), cost, "IMAGE_GENERATION", taskId,
                        "Codex 生图（" + cleanQuality + "）"));
                persist(session);
            }
            session.setStatus("queued"); session.setProgressStage("queued"); persist(session); sessions.put(taskId, session);
            try { executor.execute(() -> run(session)); }
            catch (RejectedExecutionException e) {
                fail(session, "当前生图队列已满，请稍后再试", e);
                throw new IllegalStateException("当前生图队列已满，请稍后再试");
            }
            updateQueuePositions(); return session;
        } catch (Exception e) {
            if (!sessions.containsKey(taskId)) { refund(session, "生图任务创建失败退款"); deleteDirectory(dir); }
            if (e instanceof IOException io) throw io;
            if (e instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("创建生图任务失败", e);
        }
    }

    private void prepareReference(ImageGenerationSession session, String parentTaskId, MultipartFile file, AuthUser user) throws IOException {
        if (parentTaskId != null && !parentTaskId.isBlank()) {
            ImageGenerationSession parent = requireOwned(parentTaskId, user);
            if (!"completed".equals(parent.getStatus()) || !Files.isRegularFile(parent.getResultPath())) throw new IllegalArgumentException("参考任务尚未完成");
            session.setParentTaskId(parentTaskId); session.setReferenceContentType("image/png");
            Files.copy(parent.getResultPath(), session.getReferencePath()); return;
        }
        if (file.getSize() <= 0 || file.getSize() > config.getMaxReferenceBytes()) throw new IllegalArgumentException("参考图最大 20 MB");
        byte[] head;
        try (var input = file.getInputStream()) { head = input.readNBytes(16); }
        String type = isPng(head) ? "image/png" : isJpeg(head) ? "image/jpeg" : null;
        if (type == null) throw new IllegalArgumentException("参考图仅支持 PNG 或 JPEG");
        session.setReferenceContentType(type); session.setReferenceFileName(safeName(file.getOriginalFilename()));
        file.transferTo(session.getReferencePath());
        validateImageDimensions(session.getReferencePath());
    }

    private void run(ImageGenerationSession session) {
        try {
            session.setStatus("generating"); session.setProgressStage("generating"); persist(session); send(session);
            byte[] output = "EDIT".equals(session.getMode())
                    ? client.edit(session.getPrompt(), session.getSize(), session.getQuality(), session.getReferencePath(), session.getReferenceContentType())
                    : client.generate(session.getPrompt(), session.getSize(), session.getQuality());
            Path temp = session.getTaskDir().resolve("output.png.tmp"); Files.write(temp, output); move(temp, session.getResultPath());
            createPreview(session.getResultPath(), session.getPreviewPath());
            session.setStatus("completed"); session.setProgressStage("completed"); session.setCompletedAt(System.currentTimeMillis());
            persist(session); send(session); completeEmitters(session.getTaskId()); cleanupHistory();
        } catch (Exception e) { fail(session, "生图失败，请稍后重试", e); }
        finally { updateQueuePositions(); }
    }

    private void fail(ImageGenerationSession session, String safeMessage, Exception error) {
        log.error("生图任务失败: taskId={}", session.getTaskId(), error);
        session.setStatus("failed"); session.setProgressStage("failed"); session.setErrorMessage(safeMessage); session.setCompletedAt(System.currentTimeMillis());
        refund(session, "生图任务未完成退款");
        try { persist(session); } catch (Exception persistError) { log.error("保存生图失败状态失败: {}", session.getTaskId(), persistError); }
        send(session); completeEmitters(session.getTaskId());
    }

    private void refund(ImageGenerationSession session, String reason) {
        if (session.getCreditTransactionId() == null || session.isCreditRefunded()) return;
        session.setRefundPending(true);
        try { quota.refund(session.getCreditTransactionId(), reason); session.setCreditRefunded(true); session.setRefundPending(false); session.setRefundError(null); }
        catch (Exception e) { session.setRefundError("退款待重试"); log.error("生图退款失败: taskId={}", session.getTaskId(), e); }
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
    public List<ImageGenerationSession> recent(AuthUser user) {
        if (user != null && user.isRoot()) {
            return sessions.values().stream().sorted(Comparator.comparingLong(ImageGenerationSession::getCreatedAt).reversed())
                    .limit(runtime.imageMaxGlobalHistory()).collect(Collectors.toList());
        }
        return sessions.values().stream().filter(s -> user != null && s.getUserId() == user.id())
                .sorted(Comparator.comparingLong(ImageGenerationSession::getCreatedAt).reversed())
                .limit(runtime.imageMaxHistory()).collect(Collectors.toList());
    }
    public void delete(String taskId, AuthUser user) {
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
        data.put("status", session.getStatus()); data.put("stage", session.getProgressStage()); data.put("queuePosition", session.getQueuePosition());
        data.put("error", session.getErrorMessage()); data.put("createdAt", session.getCreatedAt()); data.put("updatedAt", session.getUpdatedAt());
        data.put("creditCost", session.getCreditCost()); data.put("creditRefunded", session.isCreditRefunded());
        data.put("resultReady", "completed".equals(session.getStatus())); return data;
    }

    public synchronized void cleanupHistory() {
        List<ImageGenerationSession> terminal = sessions.values().stream()
                .filter(s -> TERMINAL.contains(s.getStatus()) && !s.isRefundPending())
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
        synchronized (session) {
            Path temp = session.getTaskDir().resolve("task.json.tmp");
            mapper.writeValue(temp.toFile(), session); move(temp, session.getMetadataPath());
        }
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
