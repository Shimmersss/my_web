package com.web.backen.github;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** GitHub 周榜/月榜的轻量磁盘存储，不把历史榜单塞进 JVM 长期缓存之外。 */
@Component
public class GithubRankingStore {

    private final ObjectMapper objectMapper;
    private final Path dataFile = Path.of(System.getProperty("user.dir"))
            .getParent()
            .resolve(".run")
            .resolve("github-rankings.json")
            .normalize();

    public GithubRankingStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized Map<String, Object> read() {
        ensureParent();
        if (!Files.exists(dataFile)) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(dataFile.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    public synchronized void write(Map<String, Object> data) {
        try {
            ensureParent();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dataFile.toFile(), data);
        } catch (IOException e) {
            throw new IllegalStateException("保存 GitHub 排行榜失败: " + e.getMessage(), e);
        }
    }

    private void ensureParent() {
        try {
            Files.createDirectories(dataFile.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("创建 GitHub 排行榜目录失败: " + e.getMessage(), e);
        }
    }
}
