package com.web.backen.github;

import com.web.backen.translate.LlmService;
import com.web.backen.auth.RuntimeConfigService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.time.Instant;

/**
 * GitHub 周榜/月榜：以当前周期新建的公开仓库为候选，按 stars 倒序记录榜单。
 * GitHub 搜索和 Mimo 摘要均在单独的有界工作线程中执行，页面接口只读已落盘快照。
 */
@Service
public class GithubRankingService {

    private static final Logger log = LoggerFactory.getLogger(GithubRankingService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int HISTORY_LIMIT = 12;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一个开源项目编辑。请根据 GitHub 仓库元数据和 README，写一段简洁、准确的简体中文项目总结。
            只输出一段 60-100 字的中文，不要 Markdown、不要夸大、不确定的功能不要臆测；优先说明项目解决什么问题、核心能力和适用场景。
            """;

    private final GithubProjectService githubProjectService;
    private final GithubRankingStore store;
    private final LlmService llmService;
    private final RuntimeConfigService runtimeConfig;
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "github-ranking-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);
    private volatile Instant lastManualRequestAt = Instant.EPOCH;

    public GithubRankingService(GithubProjectService githubProjectService,
                                GithubRankingStore store,
                                LlmService llmService,
                                RuntimeConfigService runtimeConfig) {
        this.githubProjectService = githubProjectService;
        this.store = store;
        this.llmService = llmService;
        this.runtimeConfig = runtimeConfig;
    }

    @PostConstruct
    public void warmIfNeeded() {
        requestRefresh(false);
    }

    /** 每小时检查一次；实际抓取间隔由 root 后台配置，默认每天一次。 */
    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 10 * 60 * 1000)
    public void scheduledRefresh() {
        requestRefresh(false);
    }

    public Map<String, Object> getRankings() {
        Map<String, Object> data = store.read();
        data.remove("summaryCache");
        return data;
    }

    public void requestRefresh(boolean force) {
        boolean due = refreshDue();
        if (!force && (!runtimeConfig.githubRankingEnabled() || !due)) return;
        if (force && !manualRefreshAllowed()) return;
        if (!refreshRunning.compareAndSet(false, true)) return;
        if (force) lastManualRequestAt = Instant.now();
        boolean refreshSnapshot = force || due;
        refreshExecutor.submit(() -> {
            try {
                refreshPeriod("weekly", refreshSnapshot);
                refreshPeriod("monthly", refreshSnapshot);
            } catch (Exception e) {
                log.warn("GitHub 周榜/月榜刷新失败: {}", e.getMessage());
            } finally {
                refreshRunning.set(false);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        refreshExecutor.shutdownNow();
    }

    private boolean hasCurrentPeriods() {
        Map<String, Object> data = store.read();
        String weeklyKey = periodKey("weekly", LocalDate.now(ZONE));
        String monthlyKey = periodKey("monthly", LocalDate.now(ZONE));
        return samePeriod(data.get("weekly"), weeklyKey) && samePeriod(data.get("monthly"), monthlyKey);
    }

    private boolean refreshDue() {
        Map<String, Object> data = store.read();
        if (!hasCurrentPeriods()) return true;
        String updatedAt = string(data.get("updatedAt"));
        try {
            return Duration.between(Instant.parse(updatedAt), Instant.now()).toHours()
                    >= runtimeConfig.githubRankingIntervalHours();
        } catch (Exception e) {
            return true;
        }
    }

    private boolean manualRefreshAllowed() {
        return Duration.between(lastManualRequestAt, Instant.now()).toMinutes()
                >= runtimeConfig.githubRankingManualCooldownMinutes();
    }

    private void refreshPeriod(String type, boolean force) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = periodStart(type, today);
        LocalDate end = periodEnd(type, today);
        String key = periodKey(type, today);
        Map<String, Object> current = store.read();
        if (!force && samePeriod(current.get(type), key)) {
            return;
        }

        String query = "created:" + DATE.format(start) + ".." + DATE.format(end)
                + " fork:false stars:>0";
        int limit = "weekly".equals(type)
                ? runtimeConfig.githubRankingWeeklyLimit()
                : runtimeConfig.githubRankingMonthlyLimit();
        List<Map<String, Object>> searchResults = githubProjectService.searchTopRepositories(query, limit);
        Map<String, Object> summaryCache = map(current.get("summaryCache"));
        List<Map<String, Object>> projects = new ArrayList<>();
        for (int i = 0; i < searchResults.size() && i < limit; i++) {
            Map<String, Object> project = normalizeProject(searchResults.get(i), i + 1);
            project.put("aiSummary", runtimeConfig.githubRankingAiEnabled()
                    ? summaryFor(project, summaryCache)
                    : string(project.get("description")));
            projects.add(project);
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("periodKey", key);
        snapshot.put("periodStart", start.toString());
        snapshot.put("periodEnd", end.toString());
        snapshot.put("label", type.equals("weekly") ? "本周" : "本月");
        snapshot.put("updatedAt", java.time.Instant.now().toString());
        snapshot.put("query", query);
        snapshot.put("projects", projects);

        appendHistory(current, type, snapshot);
        current.put(type, snapshot);
        current.put("summaryCache", summaryCache);
        current.put("updatedAt", java.time.Instant.now().toString());
        store.write(current);
        log.info("GitHub {} 排行榜刷新完成，{} 个项目", type, projects.size());
    }

    private String summaryFor(Map<String, Object> project, Map<String, Object> cache) {
        String repo = string(project.get("full_name"));
        Map<String, Object> cached = map(cache.get(repo));
        String cachedSummary = string(cached.get("summary"));
        if (!cachedSummary.isBlank()) return cachedSummary;

        String description = string(project.get("description"));
        String readme = "";
        try {
            String[] parts = repo.split("/", 2);
            if (parts.length == 2) {
                readme = githubProjectService.fetchReadme(parts[0], parts[1]);
            }
        } catch (Exception e) {
            log.debug("读取 {} README 失败，改用仓库元数据生成摘要", repo);
        }
        if (readme.length() > 7_000) readme = readme.substring(0, 7_000);
        String prompt = "仓库：" + repo
                + "\n描述：" + description
                + "\n语言：" + string(project.get("language"))
                + "\nTopics：" + project.getOrDefault("topics", List.of())
                + "\nREADME：\n" + readme;
        try {
            String summary = llmService.complete(SUMMARY_SYSTEM_PROMPT, prompt, 384).trim();
            if (!summary.isBlank()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("summary", summary.replaceAll("^```(?:text|markdown)?\\s*|\\s*```$", "").trim());
                entry.put("updatedAt", java.time.Instant.now().toString());
                cache.put(repo, entry);
                return string(entry.get("summary"));
            }
        } catch (Exception e) {
            log.warn("Mimo 项目摘要生成失败 [{}]: {}", repo, e.getMessage());
        }
        return description.isBlank() ? "暂无项目摘要" : description;
    }

    private Map<String, Object> normalizeProject(Map<String, Object> raw, int rank) {
        Map<String, Object> project = new LinkedHashMap<>();
        Map<String, Object> owner = map(raw.get("owner"));
        project.put("rank", rank);
        project.put("full_name", string(raw.get("full_name")));
        project.put("name", string(raw.get("name")));
        project.put("owner", string(owner.get("login")));
        project.put("html_url", string(raw.get("html_url")));
        project.put("description", string(raw.get("description")));
        project.put("stargazers_count", raw.getOrDefault("stargazers_count", 0));
        project.put("forks_count", raw.getOrDefault("forks_count", 0));
        project.put("language", string(raw.get("language")));
        project.put("topics", raw.get("topics") instanceof List<?> ? raw.get("topics") : List.of());
        project.put("created_at", string(raw.get("created_at")));
        project.put("pushed_at", string(raw.get("pushed_at")));
        return project;
    }

    private void appendHistory(Map<String, Object> data, String type, Map<String, Object> snapshot) {
        String historyKey = type + "History";
        List<Map<String, Object>> history = maps(data.get(historyKey));
        String periodKey = string(snapshot.get("periodKey"));
        history.removeIf(item -> periodKey.equals(string(item.get("periodKey"))));
        history.add(0, snapshot);
        if (history.size() > HISTORY_LIMIT) {
            history.subList(HISTORY_LIMIT, history.size()).clear();
        }
        data.put(historyKey, history);
    }

    private LocalDate periodStart(String type, LocalDate date) {
        if ("monthly".equals(type)) return date.with(TemporalAdjusters.firstDayOfMonth());
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private LocalDate periodEnd(String type, LocalDate date) {
        if ("monthly".equals(type)) return YearMonth.from(date).atEndOfMonth();
        return periodStart(type, date).plusDays(6);
    }

    private String periodKey(String type, LocalDate date) {
        return type + "-" + periodStart(type, date);
    }

    private boolean samePeriod(Object value, String key) {
        return value instanceof Map<?, ?> map && key.equals(string(map.get("periodKey")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        return new LinkedHashMap<>();
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) out.add(map(item));
        }
        return out;
    }

    private String string(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
