package com.web.backen.home;

import com.web.backen.github.GithubRankingService;
import com.web.backen.imagegen.ImageGenerationService;
import com.web.backen.ppt.PptGenerationService;
import com.web.backen.translate.TranslationService;
import com.web.backen.zotero.ZoteroCache;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Public aggregate only: never expose task owners, prompts, files or private work details on the homepage. */
@RestController
@RequestMapping("/api/home")
public class HomeStatusController {
    private final ZoteroCache zotero; private final GithubRankingService rankings;
    private final PptGenerationService ppt; private final TranslationService translation; private final ImageGenerationService image;
    private volatile Map<String, Object> cached = Map.of(); private volatile long cachedAt;
    public HomeStatusController(ZoteroCache zotero, GithubRankingService rankings, PptGenerationService ppt,
                                TranslationService translation, ImageGenerationService image) {
        this.zotero = zotero; this.rankings = rankings; this.ppt = ppt; this.translation = translation; this.image = image;
    }
    @GetMapping("/daily-status")
    public Map<String, Object> dailyStatus() {
        long now = System.currentTimeMillis(); if (now - cachedAt < 60_000 && !cached.isEmpty()) return Map.of("code", 200, "data", cached);
        Map<String, Object> github = rankings.getRankings();
        long site = Math.max(ppt.latestTaskUpdateAt(), Math.max(translation.latestTaskUpdateAt(), image.latestTaskUpdateAt()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        data.put("zoteroUpdatedAt", zotero.getItemsUpdatedAt());
        data.put("githubUpdatedAt", github.getOrDefault("updatedAt", ""));
        data.put("siteTaskUpdatedAt", site);
        data.put("zoteroReady", zotero.isWarmedUp()); data.put("githubReady", !github.isEmpty()); data.put("siteTaskReady", site > 0);
        cached = data; cachedAt = now; return Map.of("code", 200, "data", data);
    }
}
