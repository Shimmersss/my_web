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
        return List.of(
                project("vuejs/core", "Vue 3 核心框架，当前站点前端技术栈的基础。", "Frontend", true),
                project("vitejs/vite", "极速前端构建工具，本项目开发环境由 Vite 驱动。", "Tooling", true),
                project("tusen-ai/naive-ui", "Vue 3 组件库，负责项目里的主要交互控件与信息展示。", "UI", true)
        );
    }

    private Map<String, Object> project(String repo, String highlight, String category, boolean featured) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("repo", repo);
        item.put("highlight", highlight);
        item.put("category", category);
        item.put("featured", featured);
        return item;
    }
}
