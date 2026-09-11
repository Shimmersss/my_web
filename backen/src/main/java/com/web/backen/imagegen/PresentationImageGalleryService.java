package com.web.backen.imagegen;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.RuntimeConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persistent gallery for successful PPTX/HTML generated visuals, independent from task history cleanup. */
@Service
public class PresentationImageGalleryService {
    /** Only publication data crosses the module boundary, never a mutable task/session. */
    public record Publication(String taskId, long userId, String outputFormat, Path taskDir) {}

    private final ObjectMapper mapper;
    private final RuntimeConfigService runtime;
    private final Path root;

    @Autowired
    public PresentationImageGalleryService(ObjectMapper mapper, RuntimeConfigService runtime) {
        this(mapper, runtime, Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent()
                .resolve(".run/presentation-image-assets").normalize());
    }

    PresentationImageGalleryService(ObjectMapper mapper, RuntimeConfigService runtime, Path root) {
        this.mapper = mapper; this.runtime = runtime;
        this.root = root;
    }

    public synchronized void publish(Publication publication) throws IOException {
        Path manifest = publication.taskDir().resolve("generated-images/generated-image-manifest.json");
        if (!Files.isRegularFile(manifest)) return;
        Map<String, Object> payload = mapper.readValue(manifest.toFile(), new TypeReference<>() {});
        Object raw = payload.get("images");
        if (!(raw instanceof List<?> images)) return;
        Files.createDirectories(root);
        for (Object value : images) {
            if (!(value instanceof Map<?, ?> map)) continue;
            String fileName = String.valueOf(map.get("fileName") == null ? "" : map.get("fileName"));
            String id = String.valueOf(map.get("id") == null ? "" : map.get("id"));
            if (!fileName.matches("gpt-\\d+\\.png") || !id.matches("GPT\\d{2}")) continue;
            Path source = manifest.getParent().resolve(fileName).normalize();
            if (!source.startsWith(manifest.getParent()) || !Files.isRegularFile(source)) continue;
            String assetId = publication.taskId() + "-" + id.toLowerCase();
            Path dir = root.resolve(assetId);
            if (Files.isDirectory(dir) && Files.isRegularFile(dir.resolve("asset.json"))) continue;
            Files.createDirectories(dir);
            Files.copy(source, dir.resolve("output.png"), StandardCopyOption.REPLACE_EXISTING);
            createPreview(dir.resolve("output.png"), dir.resolve("preview.jpg"));
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("assetId", assetId); item.put("taskId", publication.taskId()); item.put("userId", publication.userId());
            item.put("outputFormat", publication.outputFormat()); item.put("slideId", String.valueOf(map.get("slideId") == null ? "" : map.get("slideId")));
            item.put("slideIndex", map.get("slideIndex") == null ? 0 : map.get("slideIndex")); item.put("slideTitle", String.valueOf(map.get("slideTitle") == null ? "" : map.get("slideTitle")));
            item.put("prompt", String.valueOf(map.get("prompt") == null ? "" : map.get("prompt"))); item.put("quality", runtime.imageGenerationQuality());
            item.put("createdAt", System.currentTimeMillis());
            mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("asset.json").toFile(), item);
        }
        cleanup();
    }

    public synchronized List<Map<String, Object>> recent(AuthUser user) {
        List<Map<String, Object>> all = readAll();
        return all.stream().filter(item -> user != null && (user.isRoot() || number(item.get("userId")) == user.id()))
                .sorted(Comparator.comparingLong((Map<String, Object> item) -> number(item.get("createdAt"))).reversed()).toList();
    }

    public synchronized Path file(String assetId, boolean preview, AuthUser user) {
        Map<String, Object> item = requireReadable(assetId, user);
        Path file = root.resolve(String.valueOf(item.get("assetId"))).resolve(preview ? "preview.jpg" : "output.png").normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) throw new IllegalArgumentException("演示生图素材不存在");
        return file;
    }

    public synchronized void delete(String assetId, AuthUser user) {
        Map<String, Object> item = requireReadable(assetId, user);
        if (user == null || number(item.get("userId")) != user.id()) throw new AuthException(403, "root 只能审计其他用户的演示素材");
        deleteTree(root.resolve(assetId));
    }

    public synchronized void cleanup() {
        List<Map<String, Object>> all = readAll().stream().sorted(Comparator.comparingLong((Map<String, Object> item) -> number(item.get("createdAt"))).reversed()).toList();
        Set<String> keep = new java.util.HashSet<>(); Map<Long, Integer> perUser = new HashMap<>();
        for (Map<String, Object> item : all) {
            if (keep.size() >= runtime.presentationImageMaxGlobalHistory()) break;
            long userId = number(item.get("userId")); int count = perUser.getOrDefault(userId, 0);
            if (count >= runtime.presentationImageMaxHistory()) continue;
            keep.add(String.valueOf(item.get("assetId"))); perUser.put(userId, count + 1);
        }
        all.stream().filter(item -> !keep.contains(String.valueOf(item.get("assetId")))).forEach(item -> deleteTree(root.resolve(String.valueOf(item.get("assetId")))));
    }

    private Map<String, Object> requireReadable(String assetId, AuthUser user) {
        if (assetId == null || !assetId.matches("[a-zA-Z0-9-]{6,80}")) throw new IllegalArgumentException("演示生图素材不存在");
        Map<String, Object> item = readAll().stream().filter(candidate -> assetId.equals(candidate.get("assetId"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("演示生图素材不存在"));
        if (user == null || (!user.isRoot() && number(item.get("userId")) != user.id())) throw new AuthException(403, "无权访问该演示素材");
        return item;
    }
    private List<Map<String, Object>> readAll() {
        if (!Files.isDirectory(root)) return List.of(); List<Map<String, Object>> all = new ArrayList<>();
        try (var dirs = Files.list(root)) { for (Path dir : dirs.filter(Files::isDirectory).toList()) try {
            Path metadata = dir.resolve("asset.json"); if (Files.isRegularFile(metadata)) all.add(mapper.readValue(metadata.toFile(), new TypeReference<>() {}));
        } catch (Exception ignored) { } } catch (IOException ignored) { }
        return all;
    }
    private void createPreview(Path source, Path target) throws IOException {
        BufferedImage image = ImageIO.read(source.toFile()); if (image == null) throw new IOException("演示生图无法解析");
        double ratio = Math.min(1d, 640d / Math.max(image.getWidth(), image.getHeight()));
        BufferedImage preview = new BufferedImage(Math.max(1, (int) (image.getWidth() * ratio)), Math.max(1, (int) (image.getHeight() * ratio)), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = preview.createGraphics(); graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(image, 0, 0, preview.getWidth(), preview.getHeight(), null); graphics.dispose(); ImageIO.write(preview, "jpg", target.toFile());
    }
    private long number(Object value) { try { return Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return 0; } }
    private void deleteTree(Path dir) { if (dir == null || !dir.startsWith(root)) return; try (var paths = Files.walk(dir)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); } catch (IOException ignored) { } }
}
