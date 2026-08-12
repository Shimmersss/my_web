package com.web.backen.zotero;

import com.web.backen.config.ZoteroConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ZoteroCacheTest {
    private ZoteroCache cache;
    @AfterEach void close() { if (cache != null) cache.shutdown(); }

    @Test
    void filtersDeletedCollectionsAndPublishesItemsAndGroupsAsOneSnapshot() {
        ZoteroService service = mock(ZoteroService.class);
        when(service.listCollections()).thenReturn(List.of(
                collection("ROOT0001", "Root", null, false),
                collection("CHILD001", "Child", "ROOT0001", false),
                collection("DELETED1", "Deleted", null, true),
                collection("ORPHAN01", "Orphan", "MISSING1", false)));
        when(service.listAllItems()).thenReturn(List.of(
                item("ITEM0001", "journalArticle", "Paper", null, List.of("CHILD001", "DELETED1"), false),
                item("FILE0001", "attachment", "PDF", "ITEM0001", List.of(), false),
                item("SOLO0001", "attachment", "README", null, List.of("ROOT0001"), false),
                item("GONE0001", "book", "Gone", null, List.of("ROOT0001"), true)));
        cache = new ZoteroCache(service);

        cache.refresh();

        assertTrue(cache.isWarmedUp());
        assertEquals(List.of("ROOT0001", "CHILD001", "ORPHAN01"), cache.getCollections().stream().map(c -> c.get("key")).toList());
        assertNull(cache.getCollections().get(2).get("parentCollection"));
        assertEquals(2, cache.getItems().size());
        Map<String, Object> paper = cache.getItems().stream().filter(i -> "ITEM0001".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(List.of("CHILD001"), paper.get("collections"));
        assertEquals(1, ((List<?>) paper.get("attachments")).size());
    }

    @Test
    void preservesPreviousCompleteSnapshotWhenEitherUpstreamResourceFails() {
        ZoteroService service = mock(ZoteroService.class);
        when(service.listCollections()).thenReturn(List.of(collection("ROOT0001", "Root", null, false)));
        when(service.listAllItems()).thenReturn(List.of(item("ITEM0001", "book", "Stable", null, List.of("ROOT0001"), false)))
                .thenThrow(new IllegalStateException("upstream failed"));
        cache = new ZoteroCache(service);
        cache.refresh();
        long firstUpdatedAt = cache.getItemsUpdatedAt();

        cache.refresh();

        assertEquals("Stable", cache.getItems().get(0).get("title"));
        assertEquals(firstUpdatedAt, cache.getItemsUpdatedAt());
        assertEquals("同步失败，请稍后重试", cache.getLastError());
    }

    @Test
    void servicePaginatesCollectionsUntilTheLastShortPage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ZoteroConfig config = new ZoteroConfig();
        config.setBaseUrl("https://api.zotero.test"); config.setUserId("123"); config.setApiKey("secret");
        config.setSyncPageSize(2); config.setMaxCollections(10);
        server.expect(requestTo("https://api.zotero.test/users/123/collections?start=0&limit=2"))
                .andRespond(withSuccess("[{\"key\":\"A\"},{\"key\":\"B\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.zotero.test/users/123/collections?start=2&limit=2"))
                .andRespond(withSuccess("[{\"key\":\"C\"}]", MediaType.APPLICATION_JSON));
        ZoteroService service = new ZoteroService(builder.build(), config);

        assertEquals(List.of("A", "B", "C"), service.listCollections().stream().map(item -> item.get("key")).toList());
        server.verify();
    }

    private Map<String, Object> collection(String key, String name, String parent, boolean deleted) {
        return Map.of("key", key, "data", new java.util.LinkedHashMap<>(Map.of(
                "key", key, "name", name, "parentCollection", parent == null ? false : parent, "deleted", deleted)));
    }
    private Map<String, Object> item(String key, String type, String title, String parent, List<String> collections, boolean deleted) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("itemType", type); data.put("title", title); data.put("parentItem", parent); data.put("collections", collections);
        data.put("deleted", deleted); data.put("contentType", "attachment".equals(type) ? "application/pdf" : ""); data.put("filename", title + ".pdf");
        return Map.of("key", key, "version", 1, "data", data);
    }
}
