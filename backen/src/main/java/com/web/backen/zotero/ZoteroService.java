package com.web.backen.zotero;

import com.web.backen.config.ZoteroConfig;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class ZoteroService {

    private final RestClient restClient;
    private final ZoteroConfig config;

    public ZoteroService(RestClient zoteroRestClient, ZoteroConfig config) {
        this.restClient = zoteroRestClient;
        this.config = config;
    }

    /**
     * 拉取个人库的全部 items（注意：超过 100 条会分页，下面的实现只取第一页）
     */
    public List<Map<String, Object>> listItems(int limit) {
        return restClient.get()
                .uri("/users/{userId}/items?limit={limit}&format=json", config.getUserId(), limit)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    /**
     * 拉取库里所有 collection（文件夹）
     */
    public List<Map<String, Object>> listCollections() {
        return restClient.get()
                .uri("/users/{userId}/collections", config.getUserId())
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    /**
     * 拉取指定 collection 下的 items
     */
    public List<Map<String, Object>> listItemsInCollection(String collectionKey, int limit) {
        return restClient.get()
                .uri("/users/{userId}/collections/{key}/items?limit={limit}",
                        config.getUserId(), collectionKey, limit)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    public boolean isConfigured() {
        return config.getApiKey() != null && !config.getApiKey().isBlank()
                && config.getUserId() != null && !config.getUserId().isBlank();
    }
}
