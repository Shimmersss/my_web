package com.web.backen.controller;

import com.web.backen.entity.Article;
import com.web.backen.service.ArticleService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/articles")
@CrossOrigin(origins = "*")
public class ArticleController {

    private final ArticleService articleService;

    public ArticleController(ArticleService articleService) {
        this.articleService = articleService;
    }

    /**
     * 查询所有文章
     */
    @GetMapping
    public Map<String, Object> list() {
        List<Article> articles = articleService.list();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", articles);
        result.put("message", "success");
        return result;
    }

    /**
     * 根据 ID 查询
     */
    @GetMapping("/{id}")
    public Map<String, Object> getById(@PathVariable Long id) {
        Article article = articleService.getById(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", article);
        result.put("message", "success");
        return result;
    }

    /**
     * 新增文章
     */
    @PostMapping
    public Map<String, Object> create(@RequestBody Article article) {
        articleService.save(article);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", article);
        result.put("message", "创建成功");
        return result;
    }

    /**
     * 删除文章
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        articleService.removeById(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "删除成功");
        return result;
    }
}
