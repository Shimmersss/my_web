package com.web.backen.zotero;

import com.web.backen.config.ZoteroConfig;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * 拉取条目附件文件（通常是 PDF），用于代理给前端。
     * 使用 JDK HttpClient 以自动跟随 Zotero 返回的 S3 302 重定向。
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
        HttpHeaders headers = new HttpHeaders();
        Optional<String> ct = resp.headers().firstValue("content-type");
        headers.setContentType(ct.map(MediaType::parseMediaType).orElse(MediaType.APPLICATION_PDF));
        return new ResponseEntity<>(resp.body(), headers, resp.statusCode());
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
