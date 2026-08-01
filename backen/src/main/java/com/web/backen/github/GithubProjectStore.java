package com.web.backen.github;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GithubProjectStore {

    private final ObjectMapper objectMapper;
    private final Path dataFile = Path.of(System.getProperty("user.dir"))
            .getParent()
            .resolve(".run")
            .resolve("github-projects.json")
            .normalize();

    public GithubProjectStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized List<Map<String, Object>> list() {
        ensureDataFile();
        try {
            return objectMapper.readValue(dataFile.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            return defaultProjects();
        }
    }

    public synchronized List<Map<String, Object>> save(List<Map<String, Object>> projects) {
        List<Map<String, Object>> normalized = normalize(projects);
        try {
            Files.createDirectories(dataFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dataFile.toFile(), normalized);
            return normalized;
        } catch (IOException e) {
            throw new IllegalStateException("保存 GitHub 项目失败: " + e.getMessage(), e);
        }
    }

    private void ensureDataFile() {
        if (Files.exists(dataFile)) return;
        save(defaultProjects());
    }

    private List<Map<String, Object>> normalize(List<Map<String, Object>> projects) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (projects == null) return out;
        for (Map<String, Object> item : projects) {
            String repo = normalizeRepo(value(item.get("repo")));
            if (!repo.matches("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")) continue;
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("repo", repo);
            normalized.put("highlight", value(item.get("highlight")));
            normalized.put("category", value(item.get("category")).isBlank() ? "Open Source" : value(item.get("category")));
            normalized.put("featured", Boolean.TRUE.equals(item.get("featured")));
            out.add(normalized);
        }
        return out;
    }

    private String value(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String normalizeRepo(String input) {
        if (input == null) return "";
        String repo = input.trim();
        repo = repo.replaceFirst("^https?://github\\.com/", "");
        repo = repo.replaceFirst("^git@github\\.com:", "");
        repo = repo.replaceFirst("\\.git$", "");
        repo = repo.replaceAll("[?#].*$", "");
        String[] parts = repo.split("/");
        if (parts.length < 2) return repo;
        return parts[0] + "/" + parts[1];
    }

    private List<Map<String, Object>> defaultProjects() {
        return List.of();
    }
}
