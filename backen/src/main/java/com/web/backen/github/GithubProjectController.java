package com.web.backen.github;

import com.web.backen.config.AdminConfig;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/github-projects")
@CrossOrigin(origins = "*")
public class GithubProjectController {

    private final GithubProjectService githubProjectService;
    private final AdminConfig adminConfig;

    public GithubProjectController(GithubProjectService githubProjectService, AdminConfig adminConfig) {
        this.githubProjectService = githubProjectService;
        this.adminConfig = adminConfig;
    }

    @GetMapping
    public Map<String, Object> list() {
        return ok(githubProjectService.listEnriched());
    }

    @GetMapping(value = "/{owner}/{repo}/readme", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> readme(@PathVariable String owner, @PathVariable String repo) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                    .body(githubProjectService.fetchReadme(owner, repo));
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("README 读取失败: " + e.getMessage());
        }
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        if (!isValidKey(value(body.get("key")))) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 401);
            result.put("message", "管理员密钥错误");
            return result;
        }
        return ok(Map.of("token", value(body.get("key"))));
    }

    @PutMapping
    public Map<String, Object> save(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                    @RequestBody List<Map<String, Object>> projects) {
        if (!isValidKey(key)) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 401);
            result.put("message", "未授权");
            return result;
        }
        try {
            return ok(githubProjectService.save(projects));
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", e.getMessage());
            return result;
        }
    }

    private boolean isValidKey(String key) {
        return key != null && !key.isBlank() && key.equals(adminConfig.getKey());
    }

    private String value(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        result.put("message", "success");
        return result;
    }
}
