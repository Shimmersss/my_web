package com.web.backen.github;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GithubProjectService {

    private final GithubProjectStore store;
    private final RestClient githubClient = RestClient.builder()
            .baseUrl("https://api.github.com")
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("User-Agent", "Web-Github-Projects")
            .build();
    private final RestClient rawClient = RestClient.builder()
            .baseUrl("https://raw.githubusercontent.com")
            .defaultHeader("User-Agent", "Web-Github-Projects")
            .build();

    public GithubProjectService(GithubProjectStore store) {
        this.store = store;
    }

    public List<Map<String, Object>> listEnriched() {
        List<Map<String, Object>> configured = store.list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> config : configured) {
            out.add(enrich(config));
        }
        return out;
    }

    public List<Map<String, Object>> save(List<Map<String, Object>> projects) {
        return store.save(projects);
    }

    public String fetchReadme(String owner, String repo) {
        String fullName = normalizeRepo(owner + "/" + repo);
        String defaultBranch = "main";
        try {
            Map<String, Object> meta = fetchRepoMeta(fullName);
            Object branch = meta.get("default_branch");
            if (branch != null && !branch.toString().isBlank()) {
                defaultBranch = branch.toString();
            }
        } catch (Exception ignored) {
            // README lookup falls back to common branch names.
        }

        List<String> branches = unique(List.of(defaultBranch, "main", "master"));
        List<String> filenames = List.of("README.md", "readme.md", "README.MD");
        RuntimeException last = null;
        for (String branch : branches) {
            for (String filename : filenames) {
                try {
                    return rawClient.get()
                            .uri("/{owner}/{repo}/{branch}/{filename}", owner, repo, branch, filename)
                            .retrieve()
                            .body(String.class);
                } catch (RuntimeException e) {
                    last = e;
                }
            }
        }
        throw new IllegalStateException("未找到 README.md", last);
    }

    private Map<String, Object> enrich(Map<String, Object> config) {
        String repo = normalizeRepo(value(config.get("repo")));
        Map<String, Object> out = new LinkedHashMap<>(config);
        out.put("repo", repo);
        out.put("full_name", repo);
        out.put("html_url", "https://github.com/" + repo);
        String[] parts = repo.split("/");
        out.put("owner", parts.length > 0 ? parts[0] : "");
        out.put("name", parts.length > 1 ? parts[1] : repo);

        try {
            Map<String, Object> meta = fetchRepoMeta(repo);
            copy(meta, out, "description");
            copy(meta, out, "stargazers_count");
            copy(meta, out, "forks_count");
            copy(meta, out, "open_issues_count");
            copy(meta, out, "language");
            copy(meta, out, "topics");
            copy(meta, out, "homepage");
            copy(meta, out, "pushed_at");
            copy(meta, out, "default_branch");
            copy(meta, out, "html_url");
        } catch (Exception ignored) {
            out.putIfAbsent("description", "GitHub 开源项目");
            out.putIfAbsent("stargazers_count", 0);
            out.putIfAbsent("forks_count", 0);
            out.putIfAbsent("language", "Unknown");
            out.putIfAbsent("topics", List.of());
            out.putIfAbsent("homepage", "");
            out.putIfAbsent("pushed_at", "");
        }
        return out;
    }

    private Map<String, Object> fetchRepoMeta(String repo) {
        String[] parts = normalizeRepo(repo).split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("GitHub 仓库格式错误");
        }
        return githubClient.get()
                .uri("/repos/{owner}/{repo}", parts[0], parts[1])
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private List<String> unique(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && !out.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }

    private String normalizeRepo(String input) {
        String repo = value(input);
        repo = repo.replaceFirst("^https?://github\\.com/", "");
        repo = repo.replaceFirst("^git@github\\.com:", "");
        repo = repo.replaceFirst("\\.git$", "");
        repo = repo.replaceAll("[?#].*$", "");
        String[] parts = repo.split("/");
        if (parts.length < 2) return repo;
        return parts[0] + "/" + parts[1];
    }

    private String value(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
