package com.web.backen.zotero;

import com.web.backen.config.ZoteroConfig;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ZoteroService {

    private final RestClient restClient;
    private final ZoteroConfig config;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ZoteroService(RestClient zoteroRestClient, ZoteroConfig config) {
        this.restClient = zoteroRestClient;
        this.config = config;
    }

    public List<Map<String, Object>> listItems(int limit) {
        return restClient.get()
                .uri("/users/{userId}/items?limit={limit}&format=json", config.getUserId(), limit)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    public List<Map<String, Object>> listCollections() {
        return restClient.get()
                .uri("/users/{userId}/collections", config.getUserId())
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    public List<Map<String, Object>> listItemsInCollection(String collectionKey, int limit) {
        return restClient.get()
                .uri("/users/{userId}/collections/{key}/items?limit={limit}",
                        config.getUserId(), collectionKey, limit)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    /**
     * 拉取条目附件文件，用于代理给前端。
     * - 自动跟随 Zotero 的 S3 302 重定向
     * - 如果上游返回 ZIP 包（snapshot 类型常见，如 markdown / 网页快照），
     *   自动解出包内主文件并按文件名后缀推断 content-type
     */
    public ResponseEntity<byte[]> fetchItemFile(String itemKey) throws Exception {
        URI uri = URI.create(config.getBaseUrl()
                + "/users/" + config.getUserId()
                + "/items/" + itemKey + "/file");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Zotero-API-Version", "3")
                .header("Zotero-API-Key", config.getApiKey() == null ? "" : config.getApiKey())
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = resp.body();
        String upstreamCt = resp.headers().firstValue("content-type").orElse("");

        // 检测 ZIP 头 PK\003\004
        if (body != null && body.length >= 4
                && body[0] == 0x50 && body[1] == 0x4B
                && body[2] == 0x03 && body[3] == 0x04) {
            UnzipResult unzipped = unzipPickFirstUseful(body);
            if (unzipped != null) {
                HttpHeaders h = new HttpHeaders();
                h.setContentType(MediaType.parseMediaType(guessContentType(unzipped.filename)));
                return new ResponseEntity<>(unzipped.bytes, h, 200);
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                upstreamCt.isBlank() ? "application/pdf" : upstreamCt));
        return new ResponseEntity<>(body, headers, resp.statusCode());
    }

    private record UnzipResult(String filename, byte[] bytes) {}

    /** 解 ZIP，返回第一个非目录条目（一般 Zotero snapshot 就一个主文件） */
    private UnzipResult unzipPickFirstUseful(byte[] zip) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                return new UnzipResult(e.getName(), zis.readAllBytes());
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }

    private String guessContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".md") || name.endsWith(".markdown")) return "text/markdown; charset=UTF-8";
        if (name.endsWith(".txt")) return "text/plain; charset=UTF-8";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (name.endsWith(".json")) return "application/json; charset=UTF-8";
        if (name.endsWith(".csv")) return "text/csv; charset=UTF-8";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    /**
     * 用 Zotero 官方 API 导出引用。
     * format: bibtex | ris | bibliography
     *   - bibtex / ris：直接走 ?format=bibtex|ris，返回纯文本
     *   - bibliography：走 ?include=bib&style=xxx，返回 JSON，解析后取 .bib（HTML 片段）
     */
    public String exportItem(String itemKey, String format, String style) {
        if ("bibliography".equals(format)) {
            String s = (style == null || style.isBlank()) ? "apa" : style;
            Map<String, Object> resp = restClient.get()
                    .uri("/users/{userId}/items/{key}?include=bib&style={style}",
                            config.getUserId(), itemKey, s)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            Object bib = resp == null ? null : resp.get("bib");
            return bib == null ? "" : bib.toString();
        }
        return restClient.get()
                .uri("/users/{userId}/items/{key}?format={fmt}",
                        config.getUserId(), itemKey, format)
                .retrieve()
                .body(String.class);
    }

    public boolean isConfigured() {
        return config.getApiKey() != null && !config.getApiKey().isBlank()
                && config.getUserId() != null && !config.getUserId().isBlank();
    }
}
