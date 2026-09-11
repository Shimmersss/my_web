package com.web.backen.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.settings.RuntimeConfigService;
import com.web.backen.config.ImageGenerationConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

@Component
public class OpenAiImageClient {
    private com.web.backen.runtime.TaskCoordinator coordinator = com.web.backen.runtime.TaskCoordinator.local();
    @org.springframework.beans.factory.annotation.Autowired
    void coordinator(com.web.backen.runtime.TaskCoordinator coordinator) { this.coordinator = coordinator; }

    private final RuntimeConfigService runtime;
    private final ImageGenerationConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    @Autowired
    public OpenAiImageClient(RuntimeConfigService runtime, ImageGenerationConfig config, ObjectMapper objectMapper) {
        this(runtime, config, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    OpenAiImageClient(RuntimeConfigService runtime, ImageGenerationConfig config, ObjectMapper objectMapper, HttpClient http) {
        this.runtime = runtime; this.config = config; this.objectMapper = objectMapper; this.http = http;
    }

    public byte[] generate(String prompt, String size, String quality) throws IOException, InterruptedException {
        ensureConfigured();
        byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "model", runtime.imageGenerationModel(), "prompt", prompt,
                "size", size, "quality", quality, "output_format", "png", "n", 1));
        HttpRequest request = request(runtime.imageGenerationEndpoint(), "application/json",
                HttpRequest.BodyPublishers.ofByteArray(body));
        return execute(request);
    }

    public byte[] edit(String prompt, String size, String quality, Path reference, String contentType)
            throws IOException, InterruptedException {
        return edit(prompt, size, quality, List.of(reference), List.of(contentType));
    }

    public byte[] edit(String prompt, String size, String quality, List<Path> references, List<String> contentTypes)
            throws IOException, InterruptedException {
        ensureConfigured();
        if (references == null || references.isEmpty() || references.size() > 4 || contentTypes == null || contentTypes.size() != references.size()) {
            throw new IllegalArgumentException("参考图数量需为 1–4 张");
        }
        String boundary = "----WebImage" + UUID.randomUUID().toString().replace("-", "");
        HttpRequest.BodyPublisher body = multipartPublisher(boundary, prompt, size, quality, references, contentTypes);
        HttpRequest request = request(runtime.imageEditEndpoint(), "multipart/form-data; boundary=" + boundary,
                body);
        return execute(request);
    }

    private HttpRequest request(String endpoint, String contentType, HttpRequest.BodyPublisher publisher) {
        return HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(Math.max(30, config.getTimeoutSeconds())))
                .header("Authorization", "Bearer " + runtime.imageGenerationKey())
                .header("Content-Type", contentType).POST(publisher).build();
    }

    private byte[] execute(HttpRequest request) throws IOException, InterruptedException {
        try (var permit = coordinator.network()) {
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        long encodedLimit = Math.max(1024 * 1024, config.getMaxOutputBytes() * 2);
        byte[] responseBytes;
        try (InputStream input = response.body()) { responseBytes = readLimited(input, encodedLimit); }
        String body = new String(responseBytes, StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ImageProviderException("Images API HTTP " + response.statusCode() + ": " + concise(body));
        }
        Map<String, Object> root = objectMapper.readValue(body, new TypeReference<>() {});
        Object data = root.get("data");
        if (!(data instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> image)) {
            throw new ImageProviderException("Images API 未返回图片数据");
        }
        if (image.get("url") != null && image.get("b64_json") == null) {
            throw new ImageProviderException("Images API 仅返回远程 URL，本站拒绝下载");
        }
        Object encoded = image.get("b64_json");
        if (!(encoded instanceof String value) || value.isBlank()) {
            throw new ImageProviderException("Images API 返回不包含 b64_json");
        }
        byte[] result;
        try { result = Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException e) { throw new ImageProviderException("Images API 返回了无效图片编码"); }
        validatePng(result);
        return result;

        }
    }

    private HttpRequest.BodyPublisher multipartPublisher(String boundary, String prompt, String size, String quality,
                                                          List<Path> references, List<String> contentTypes) throws IOException {
        List<HttpRequest.BodyPublisher> parts = new java.util.ArrayList<>();
        field(parts, boundary, "model", runtime.imageGenerationModel());
        field(parts, boundary, "prompt", prompt);
        field(parts, boundary, "size", size);
        field(parts, boundary, "quality", quality);
        field(parts, boundary, "output_format", "png");
        field(parts, boundary, "n", "1");
        for (int index = 0; index < references.size(); index++) {
            Path reference = references.get(index);
            String contentType = contentTypes.get(index);
            if (!Files.isRegularFile(reference)) throw new IOException("参考图文件不存在");
            parts.add(HttpRequest.BodyPublishers.ofByteArray(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image[]\"; filename=\"reference-"
                    + (index + 1) + ("image/jpeg".equals(contentType) ? ".jpg" : ".png") + "\"\r\nContent-Type: " + contentType
                    + "\r\n\r\n").getBytes(StandardCharsets.UTF_8)));
            parts.add(HttpRequest.BodyPublishers.ofFile(reference));
            parts.add(HttpRequest.BodyPublishers.ofByteArray("\r\n".getBytes(StandardCharsets.UTF_8)));
        }
        parts.add(HttpRequest.BodyPublishers.ofByteArray(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
        return HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
    }

    private void field(List<HttpRequest.BodyPublisher> parts, String boundary, String name, String value) {
        parts.add(HttpRequest.BodyPublishers.ofByteArray(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] readLimited(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int read; (read = input.read(buffer)) >= 0;) {
            total += read;
            if (total > limit) throw new ImageProviderException("Images API 响应体积超限");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private void validatePng(byte[] image) {
        if (image.length < 128 || image.length > config.getMaxOutputBytes()
                || image[0] != (byte) 0x89 || image[1] != 0x50 || image[2] != 0x4e || image[3] != 0x47) {
            throw new ImageProviderException("Images API 返回的图片不是受支持的 PNG，或体积超限");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new ImageProviderException("Images API 返回的 PNG 无法解析");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > config.getMaxReferencePixels()) throw new ImageProviderException("Images API 返回的 PNG 像素尺寸超限");
            } finally { reader.dispose(); }
        } catch (IOException e) {
            throw new ImageProviderException("Images API 返回的 PNG 无法解析");
        }
    }

    private void ensureConfigured() {
        if (runtime.imageGenerationKey() == null || runtime.imageGenerationKey().isBlank()) {
            throw new ImageProviderException("Images API Key 未配置");
        }
    }

    private String concise(String raw) {
        String result = raw == null ? "" : raw.replace(runtime.imageGenerationKey(), "***")
                .replaceAll("[\\r\\n\\t]+", " ").trim();
        try {
            Map<String, Object> root = objectMapper.readValue(result, new TypeReference<>() {});
            Object error = root.get("error");
            if (error instanceof Map<?, ?> map && map.get("message") != null) result = String.valueOf(map.get("message"));
        } catch (Exception ignored) {}
        return result.isBlank() ? "上游未返回详情" : result.substring(0, Math.min(360, result.length()));
    }

    public static class ImageProviderException extends IllegalStateException {
        public ImageProviderException(String message) { super(message); }
    }
}
