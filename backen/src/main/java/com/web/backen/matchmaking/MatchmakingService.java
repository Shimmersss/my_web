package com.web.backen.matchmaking;

import com.web.backen.runtime.TaskCoordinator;
import com.web.backen.runtime.RuntimePaths;
import com.web.backen.runtime.AtomicTaskStore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.settings.RuntimeConfigService;
import com.web.backen.ai.OpenAiImageClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

@Service
@org.springframework.context.annotation.DependsOn("matchmakingSchemaMigration")
public class MatchmakingService {
    private TaskCoordinator coordinator = TaskCoordinator.local();
    private AtomicTaskStore snapshots = new AtomicTaskStore(new ObjectMapper());
    private RuntimePaths runtimePaths = new RuntimePaths("");

    @org.springframework.beans.factory.annotation.Autowired
    void infrastructure(TaskCoordinator coordinator, AtomicTaskStore snapshots, RuntimePaths runtimePaths) {
        this.coordinator = coordinator; this.snapshots = snapshots; this.runtimePaths = runtimePaths;
        coordinator.register("matchmaking", () -> Map.of("queued", tasks.values().stream().filter(t -> "queued".equals(t.status)).count(),
                "running", tasks.values().stream().filter(t -> "running".equals(t.status)).count(),
                "pendingCompensation", tasks.values().stream().filter(t -> t.compensationPending).count(),
                "workerAvailable", worker != null && worker.isAlive()));
    }

    private static final Logger log = LoggerFactory.getLogger(MatchmakingService.class);
    static final String REPORT_VERSION = "market-positioning-v3";
    static final String RELATIONSHIP_REPORT_VERSION = "relationship-exploration-v1";
    static final String CONTENT_VERSION = "relationship-content-v1";
    private static final int QUEUE_CAPACITY = 3;
    private static final int MAX_LISTED_TASKS = 10;
    /** Terminal task snapshots only feed the "recent tasks" list; reports live in MySQL, so a day is enough. */
    private static final long TASK_RETENTION_MS = 24L * 3600 * 1000;
    private static final Set<String> GENDERS = Set.of("男", "女", "不愿透露");
    private static final Set<String> APPEARANCE_SELF = Set.of("一般", "中上", "出众", "不愿评价");
    private static final Set<String> MARITAL_STATUS = Set.of("未婚", "离异无子女", "离异有子女", "丧偶", "其他");
    private static final Set<String> JOB_TYPES = Set.of("公务员", "事业编", "国企", "外企", "民营大厂", "民营中小企业", "自由职业", "个体经营", "其他");
    private static final Set<String> WORK_INTENSITY = Set.of("965", "996", "大小周", "倒班", "经常出差", "自由安排");
    private static final Set<String> CAR_STATUS = Set.of("无", "有", "有贷款");
    private static final Set<String> ONLY_CHILD = Set.of("是", "否", "不愿透露");
    private static final Set<String> INCOME_BANDS = Set.of("3千以下", "3千-5千", "5千-8千", "8千-1万", "1万-1万5", "1万5-2万", "2万-3万", "3万-5万", "5万以上", "不愿透露");
    private static final Set<String> SMOKING = Set.of("不吸烟", "偶尔吸烟", "经常吸烟", "不愿透露");
    private static final Set<String> DRINKING = Set.of("不饮酒", "偶尔饮酒", "经常饮酒", "不愿透露");
    private static final Set<String> COHABITATION = Set.of("婚后与父母同住", "婚后分开住", "同小区就近住", "未想好", "不愿透露");
    private static final Set<String> PORTRAIT_GENDER = Set.of("女", "男", "不指定");
    private static final Set<String> PORTRAIT_AGE = Set.of("22-26岁", "27-31岁", "32-36岁", "36岁以上", "不指定");
    private static final Set<String> PORTRAIT_STYLE = Set.of("温柔亲切", "干练知性", "阳光活力", "沉稳安静", "不指定");
    private static final Set<String> PORTRAIT_HAIR = Set.of("长发", "短发", "扎发或盘发", "不指定");
    private static final Set<String> PORTRAIT_SCENE = Set.of("日常休闲", "职业装", "咖啡馆约会", "户外自然", "不指定");
    private static final Set<String> HOUSING_OPTIONS = Set.of("租住", "自有房有月供", "自有房无贷款", "与父母同住", "其他");
    private static final Set<String> PARENTS_PENSION = Set.of("有稳定退休金", "有部分", "无", "不愿透露");
    private static final Set<String> PARENTS_HEALTH = Set.of("健康", "一般", "需要照顾", "不愿透露");
    private static final Set<String> PARENTS_SUPPORT = Set.of("资助购房+帮带娃均可", "可资助购房", "可帮带娃", "暂无支持", "不愿透露");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MatchmakingReportAgent agent;
    private final QuotaService quota;
    private final OpenAiImageClient imageClient;
    private final TransactionTemplate transactions;
    private final RuntimeConfigService runtime;
    private final MatchmakingTrialService trials;
    private final MatchmakingTarotService tarot;
    private final String storageDir;
    private final LinkedBlockingQueue<MatchmakingTask> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private Thread worker;
    private final Map<String, MatchmakingTask> tasks = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public MatchmakingService(JdbcTemplate jdbc, ObjectMapper mapper, MatchmakingReportAgent agent, QuotaService quota,
                              OpenAiImageClient imageClient, TransactionTemplate transactions,
                              RuntimeConfigService runtime,
                              MatchmakingTrialService trials, MatchmakingTarotService tarot,
                              @Value("${matchmaking.storage-dir:../.run/matchmaking-tasks}") String storageDir) {
        this.jdbc = jdbc; this.mapper = mapper; this.agent = agent; this.quota = quota;
        this.imageClient = imageClient; this.transactions = transactions != null ? transactions
                : jdbc == null ? null : new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())); this.runtime = runtime;
        this.trials = trials; this.tarot = tarot; this.storageDir = storageDir;
    }

    /** Compatibility constructor retained for focused legacy tests and callers. */
    public MatchmakingService(JdbcTemplate jdbc, ObjectMapper mapper, MatchmakingReportAgent agent, QuotaService quota,
                              OpenAiImageClient imageClient, TransactionTemplate transactions,
                              RuntimeConfigService runtime, MatchmakingTrialService trials, String storageDir) {
        this(jdbc, mapper, agent, quota, imageClient, transactions, runtime, trials, null, storageDir);
    }

    @PostConstruct void init() {
        recoverInterruptedTasks();
        worker = new Thread(this::workerLoop, "matchmaking-worker");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy void shutdown() {
        if (worker == null) return;
        worker.interrupt();
        try {
            worker.join(10_000);
            if (worker.isAlive()) throw new IllegalStateException("婚恋后台任务尚未停止，必须保留运行状态");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Import legacy snapshots before recovery; SQL becomes the sole source of truth. */
    private void recoverInterruptedTasks() {
        File[] files = runtimePaths.resolve(storageDir).toFile().listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) for (File file : files) {
            try {
                MatchmakingTask task = decode(mapper.readValue(file, new TypeReference<Map<String, Object>>() {}));
                transactions.executeWithoutResult(status -> {
                    Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_tasks WHERE id=?", Integer.class, task.id);
                    if (found == null || found == 0) insertTask(task);
                });
                Files.delete(file.toPath());
                Files.deleteIfExists(new File(file.getPath() + ".tmp").toPath());
            } catch (Exception e) { throw new IllegalStateException("婚恋旧任务导入失败: " + file.getName(), e); }
        }
        for (String payload : jdbc.queryForList("SELECT payload FROM matchmaking_tasks", String.class)) {
            MatchmakingTask task;
            try { task = decode(mapper.readValue(payload, new TypeReference<Map<String, Object>>() {})); }
            catch (Exception e) { throw new IllegalStateException("婚恋任务恢复失败", e); }
            tasks.put(task.id, task);
            if ("queued".equals(task.status) || "running".equals(task.status)) {
                task.status = "error"; task.stage = "error";
                task.updatedAt = Instant.now().toString();
                task.compensationPending = true;
            }
            if (terminal(task.status)) task.request = Map.of();
            if (task.compensationPending) settleFailure(task);
            else persist(task);
        }
        pruneFinishedTasks();
    }

    private MatchmakingTask decode(Map<String, Object> snapshot) { return MatchmakingTask.fromSnapshot(snapshot); }
    private static boolean terminal(String status) { return "done".equals(status) || "error".equals(status); }

    private static boolean stale(MatchmakingTask task, Instant cutoff) {
        if (!terminal(task.status) || task.compensationPending) return false;
        try { return Instant.parse(task.updatedAt).isBefore(cutoff); }
        catch (Exception e) { return true; }
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Shanghai")
    public synchronized void pruneFinishedTasks() {
        Instant cutoff = Instant.now().minusMillis(TASK_RETENTION_MS);
        for (MatchmakingTask task : List.copyOf(tasks.values())) {
            if (stale(task, cutoff)) {
                jdbc.update("DELETE FROM matchmaking_tasks WHERE id=?", task.id);
                tasks.remove(task.id);
            }
        }
    }

    @Scheduled(fixedDelay = 60000)
    public synchronized void retryCompensations() {
        for (MatchmakingTask task : tasks.values()) {
            if ("error".equals(task.status) && task.compensationPending) {
                try { settleFailure(task); }
                catch (Exception e) { log.error("婚恋任务 {} 补偿状态保存失败，下轮重试", task.id, e); }
            }
        }
    }

    private void workerLoop() {
        while (true) {
            try {
                MatchmakingTask task = queue.take();
                if ("queued".equals(task.status)) run(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) { log.error("婚恋 worker 任务处理异常", e); }
        }
    }

    private void run(MatchmakingTask task) {
        if (isRelationshipTask(task)) {
            runRelationship(task);
            return;
        }
        try {
            setStage(task, "scoring");
            Map<String, Object> profile = new LinkedHashMap<>(task.request); // Validated before transactional admission.
            Map<String, Object> context = MatchmakingBenchmarks.context(string(profile, "city"));
            Map<String, Object> ledger = calculate(profile);
            Map<String, Object> signals = marketSignals(profile, context);
            Map<String, Object> scores = MatchmakingScoring.score(profile);
            setStage(task, "writing");
            Map<String, Object> agentInput = Map.of(
                    "cityContext", context,
                    "profile", agentProfile(profile, signals),
                    "calculatedLedger", agentLedger(ledger),
                    "scores", scores);
            Map<String, Object> narrative = agent.write(agentInput);
            String partnerImage = null;
            if (task.includePartnerImage) {
                setStage(task, "illustrating");
                partnerImage = generatePartnerImage(profile, narrative);
            }
            setStage(task, "saving");
            String reportId = UUID.randomUUID().toString();
            String profileId = UUID.randomUUID().toString();
            Instant expiry = Instant.now().plusSeconds(30L * 24 * 3600);
            boolean hasImage = partnerImage != null;
            String profilePayload;
            String reportPayload;
            try {
                profilePayload = mapper.writeValueAsString(profile);
                reportPayload = mapper.writeValueAsString(buildReport(task, reportId, profile, context, ledger, scores, narrative, partnerImage, expiry));
            } catch (Exception e) { throw new IllegalStateException("报告序列化失败", e); }
            synchronized (this) {
                if (!tasks.containsKey(task.id) || !"running".equals(task.status)) return;
                MatchmakingTask completed = decode(task.snapshot());
                completed.reportId = reportId; completed.status = "done"; completed.stage = "done";
                completed.updatedAt = Instant.now().toString(); completed.request = Map.of();
                transactions.executeWithoutResult(status -> {
                    jdbc.update("INSERT INTO matchmaking_profiles(id,user_id,payload,expires_at) VALUES(?,?,?,?)",
                            profileId, task.userId, profilePayload, Timestamp.from(expiry));
                    jdbc.update("INSERT INTO matchmaking_reports(id,profile_id,user_id,report_payload,source_version,total_score,level,city,has_image,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                            reportId, profileId, task.userId, reportPayload,
                            MatchmakingBenchmarks.VERSION, ((Number) scores.getOrDefault("total", 0)).doubleValue(),
                            String.valueOf(scores.getOrDefault("level", "")), string(profile, "city"), hasImage, Timestamp.from(expiry));
                    if (task.trial) trials.markCompleted(task.userId, task.id, reportId);
                    persist(completed);
                });
                task.reportId = completed.reportId; task.status = completed.status; task.stage = completed.stage;
                task.updatedAt = completed.updatedAt; task.request = Map.of();
            }
            pruneReportsQuietly();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().replaceAll("[\\r\\n]+", " ");
            log.warn("婚恋报告任务 {} 失败: {}", task.id, message.substring(0, Math.min(180, message.length())));
            synchronized (this) {
                if (!tasks.containsKey(task.id) || "error".equals(task.status)) return;
                task.reportId = "";
                task.status = "error"; task.stage = "error";
                task.updatedAt = Instant.now().toString();
                task.compensationPending = true;
                settleFailure(task);
            }
        }
    }

    private void runRelationship(MatchmakingTask task) {
        try {
            setStage(task, "scoring");
            Map<String, Object> profile = new LinkedHashMap<>(task.request);
            Map<String, Object> personality = RelationshipPersonality.summarize(
                    string(profile, "personalityMode"), string(profile, "declaredType"), map(profile.get("personalityAnswers")));
            List<Map<String, Object>> preferences = relationshipPreferences(profile);
            Map<String, Object> draw = map(profile.get("tarot"));
            setStage(task, "writing");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("reportVersion", RELATIONSHIP_REPORT_VERSION);
            input.put("questionnaireVersion", string(profile, "questionnaireVersion"));
            input.put("personalityVersion", string(profile, "personalityVersion"));
            input.put("contentVersion", CONTENT_VERSION);
            input.put("profile", relationshipAgentProfile(profile));
            input.put("personality", personality);
            input.put("personalityEvidence", RelationshipQuestionnaire.PERSONALITY_QUESTIONS.stream()
                    .filter(q -> map(profile.get("personalityAnswers")).containsKey(q.id()))
                    .map(q -> Map.of("id", q.id(), "question", q.prompt(), "answer",
                            number(map(profile.get("personalityAnswers")), q.id()) == 1 ? q.left() : q.right())).toList());
            input.put("relationshipPreferences", preferences);
            input.put("tarot", draw);
            input.put("titleCandidates", RelationshipQuestionnaire.catalogue().get("titles"));
            Map<String, Object> narrative = agent.write(input);
            String partnerImage = null;
            if (task.includePartnerImage) {
                setStage(task, "illustrating");
                partnerImage = generatePartnerImage(profile, narrative);
            }
            setStage(task, "saving");
            String reportId = UUID.randomUUID().toString();
            String profileId = UUID.randomUUID().toString();
            Instant expiry = Instant.now().plusSeconds(30L * 24 * 3600);
            String profilePayload;
            String reportPayload;
            try {
                profilePayload = mapper.writeValueAsString(safeRelationshipProfile(profile));
                reportPayload = mapper.writeValueAsString(buildRelationshipReport(
                        task, reportId, profile, personality, preferences, draw, narrative, partnerImage, expiry));
            } catch (Exception e) { throw new IllegalStateException("关系报告序列化失败", e); }
            synchronized (this) {
                if (!tasks.containsKey(task.id) || !"running".equals(task.status)) return;
                MatchmakingTask completed = MatchmakingTask.fromSnapshot(task.snapshot());
                completed.reportId = reportId; completed.status = "done"; completed.stage = "done";
                completed.updatedAt = Instant.now().toString(); completed.request = Map.of();
                String savedPartnerImage = partnerImage;
                transactions.executeWithoutResult(status -> {
                    jdbc.update("INSERT INTO matchmaking_profiles(id,user_id,payload,expires_at) VALUES(?,?,?,?)",
                            profileId, task.userId, profilePayload, Timestamp.from(expiry));
                    Map<?, ?> identity = narrative.get("identity") instanceof Map<?, ?> m ? m : Map.of();
                    String title = text(identity.containsKey("titleId") ? identity.get("titleId") : identity.get("title"));
                    String label = text(personality.get("label"));
                    jdbc.update("""
                            INSERT INTO matchmaking_reports
                              (id,profile_id,user_id,report_payload,source_version,report_version,title,personality_label,
                               total_score,level,city,has_image,expires_at)
                            VALUES(?,?,?,?,?,?,?,?,NULL,NULL,?,?,?)
                            """, reportId, profileId, task.userId, reportPayload, CONTENT_VERSION,
                            RELATIONSHIP_REPORT_VERSION, title.isBlank() ? null : title,
                            label.isBlank() ? null : label, null, savedPartnerImage != null, Timestamp.from(expiry));
                    if (tarot != null) tarot.markReport(task.tarotDrawId, task.id, reportId);
                    if (task.trial) trials.markCompleted(task.userId, task.id, reportId);
                    persist(completed);
                });
                task.reportId = completed.reportId; task.status = completed.status; task.stage = completed.stage;
                task.updatedAt = completed.updatedAt; task.request = Map.of();
            }
            pruneReportsQuietly();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().replaceAll("[\\r\\n]+", " ");
            log.warn("关系探索报告任务 {} 失败: {}", task.id, message.substring(0, Math.min(180, message.length())));
            synchronized (this) {
                if (!tasks.containsKey(task.id) || "error".equals(task.status)) return;
                task.reportId = ""; task.status = "error"; task.stage = "error";
                task.updatedAt = Instant.now().toString(); task.compensationPending = true;
                settleFailure(task);
            }
        }
    }

    private Map<String, Object> buildReport(MatchmakingTask task, String reportId, Map<String, Object> profile,
                                            Map<String, Object> context, Map<String, Object> ledger,
                                            Map<String, Object> scores, Map<String, Object> narrative,
                                            String partnerImage, Instant expiry) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", reportId); report.put("reportVersion", REPORT_VERSION);
        report.put("profile", safeProfile(profile)); report.put("context", context);
        report.put("ledger", ledger); report.put("scores", scores); report.put("narrative", narrative);
        if (partnerImage != null) report.put("partnerImage", partnerImage);
        report.put("createdAt", Instant.now().toString()); report.put("expiresAt", expiry.toString());
        return report;
    }

    private Map<String, Object> buildRelationshipReport(MatchmakingTask task, String reportId, Map<String, Object> profile,
                                                        Map<String, Object> personality, List<Map<String, Object>> preferences,
                                                        Map<String, Object> draw, Map<String, Object> narrative,
                                                        String partnerImage, Instant expiry) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", reportId); report.put("reportVersion", RELATIONSHIP_REPORT_VERSION);
        report.put("questionnaireVersion", string(profile, "questionnaireVersion"));
        report.put("personalityVersion", string(profile, "personalityVersion"));
        report.put("contentVersion", CONTENT_VERSION); report.put("deckVersion", TarotDeck.VERSION);
        report.put("profile", safeRelationshipProfile(profile)); report.put("personality", personality);
        report.put("relationshipPreferences", preferences); report.put("tarot", draw); report.put("narrative", narrative);
        for (String key : List.of("identity", "tarotReadings", "relationshipManual", "recurringPatterns", "attraction", "nextSteps")) {
            if (narrative.containsKey(key)) report.put(key, narrative.get(key));
        }
        if (!map(profile.get("lifeContext")).isEmpty()) report.put("lifeLedger", lifeLedger(profile));
        if (partnerImage != null) report.put("partnerImage", partnerImage);
        report.put("createdAt", Instant.now().toString()); report.put("expiresAt", expiry.toString());
        return report;
    }

    private List<Map<String, Object>> relationshipPreferences(Map<String, Object> profile) {
        Map<String, Object> answers = map(profile.get("relationshipAnswers"));
        return RelationshipQuestionnaire.RELATIONSHIP_QUESTIONS.stream().map(question -> {
            int value = (int) number(answers, question.id());
            String tendency = value == 0 ? "两端都可能" : value < 0 ? question.left() : question.right();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", question.id()); item.put("name", question.name()); item.put("answerValue", value);
            item.put("tendencyLabel", tendency); item.put("left", question.left()); item.put("right", question.right());
            return item;
        }).toList();
    }

    private Map<String, Object> relationshipAgentProfile(Map<String, Object> profile) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("relationshipStage", "explorationIntent", "personalityMode", "declaredType", "personalNote")) {
            Object value = profile.get(key);
            if (value != null && !String.valueOf(value).isBlank()) result.put(key, value);
        }
        if (!map(profile.get("lifeContext")).isEmpty()) result.put("lifeContext", profile.get("lifeContext"));
        if (!map(profile.get("partnerPreferences")).isEmpty()) result.put("partnerPreferences", profile.get("partnerPreferences"));
        return result;
    }

    private Map<String, Object> safeRelationshipProfile(Map<String, Object> profile) {
        Map<String, Object> result = new LinkedHashMap<>(relationshipAgentProfile(profile));
        result.remove("personalNote");
        if (!string(profile, "personalNote").isBlank()) result.put("personalNote", string(profile, "personalNote"));
        return result;
    }

    private Map<String, Object> lifeLedger(Map<String, Object> profile) {
        Map<String, Object> context = map(profile.get("lifeContext"));
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("city", context.getOrDefault("city", null));
        ledger.put("workRhythm", context.getOrDefault("workRhythm", null));
        ledger.put("livingExpectation", context.getOrDefault("livingExpectation", null));
        ledger.put("missing", context.isEmpty() ? "未填写生活底稿" : "只展示你主动提供的生活信息");
        return ledger;
    }

    private synchronized void setStage(MatchmakingTask task, String stage) {
        if (!tasks.containsKey(task.id) || terminal(task.status)) throw new IllegalStateException("任务已取消");
        task.status = "running"; task.stage = stage; task.updatedAt = Instant.now().toString();
        persist(task);
    }

    private void settleFailure(MatchmakingTask task) {
        task.request = Map.of();
        task.compensationPending = true;
        task.error = task.trial ? "正在恢复免费生成资格，请稍后重试" : "报告生成未完成，积分退还处理中";
        // Reconcile before compensation: a lost commit acknowledgement must not refund a saved report.
        boolean needsCompensation = Boolean.TRUE.equals(transactions.execute(status -> {
            MatchmakingTask durable = durableTask(task.id);
            if ("done".equals(durable.status)) {
                task.status = durable.status; task.stage = durable.stage; task.reportId = durable.reportId;
                task.updatedAt = durable.updatedAt; task.error = ""; task.compensationPending = false;
                return false;
            }
            persist(task);
            return true;
        }));
        if (!needsCompensation) return;
        try {
            transactions.executeWithoutResult(status -> {
                if (task.trial) trials.markRetryable(task.userId, task.id);
                else if (task.transactionId > 0) quota.refund(task.transactionId, "婚恋报告任务失败退款");
                if (isRelationshipTask(task) && tarot != null) tarot.restoreAfterFailure(task.tarotDrawId, task.id);
                task.compensationPending = false;
                task.error = task.trial ? "报告生成未完成，可免费重试" : "报告生成未完成，积分已退还";
                persist(task);
            });
        } catch (Exception e) {
            task.compensationPending = true;
            task.error = task.trial ? "正在恢复免费生成资格，请稍后重试" : "报告生成未完成，积分退还处理中";
            log.error("婚恋任务 {} 补偿待重试", task.id, e);
            // The preceding transaction already preserved the pending state for restart/retry.
        }
    }

    private MatchmakingTask durableTask(String id) {
        String saved = jdbc.queryForObject("SELECT payload FROM matchmaking_tasks WHERE id=? FOR UPDATE", String.class, id);
        try { return decode(mapper.readValue(saved, new TypeReference<Map<String, Object>>() {})); }
        catch (Exception e) { throw new IllegalStateException("无法核对婚恋任务状态", e); }
    }

    private String snapshot(MatchmakingTask task) {
        try { return mapper.writeValueAsString(task.snapshot()); }
        catch (Exception e) { throw new IllegalStateException("婚恋任务序列化失败", e); }
    }

    private void insertTask(MatchmakingTask task) {
        jdbc.update("INSERT INTO matchmaking_tasks(id,user_id,payload) VALUES (?,?,?)", task.id, task.userId, snapshot(task));
    }

    private void persist(MatchmakingTask task) {
        if (jdbc.update("UPDATE matchmaking_tasks SET payload=? WHERE id=?", snapshot(task), task.id) != 1)
            throw new IllegalStateException("婚恋任务记录不存在");
    }

    public Map<String, Object> catalogue() {
        Map<String, Object> result = new LinkedHashMap<>(MatchmakingBenchmarks.catalogue());
        result.putAll(RelationshipQuestionnaire.catalogue());
        result.put("tarotDeckVersion", TarotDeck.VERSION);
        return result;
    }
    public Map<String, Object> drawTarot(AuthUser user) {
        if (tarot == null) throw new AuthException(503, "塔罗牌阵服务暂不可用");
        if (runtime != null && !runtime.relationshipEnabled()) throw new AuthException(403, "关系探索报告暂未开放");
        return tarot.draw(user.id());
    }
    public Map<String, Object> currentTarot(AuthUser user) {
        if (tarot == null) { Map<String, Object> empty = new LinkedHashMap<>(); empty.put("draw", null); return empty; }
        return tarot.current(user.id());
    }

    // Synchronize admission with deletion/finalization; the service intentionally has one worker instance.
    public synchronized Map<String, Object> createTask(AuthUser user, Map<String, Object> body) {
        try (var admission = coordinator.admit()) {
        cleanExpired();
        boolean busy = tasks.values().stream().anyMatch(t -> t.userId == user.id()
                && ("queued".equals(t.status) || "running".equals(t.status)));
        if (!user.isMatchmakingTrial() && busy) throw new AuthException(429, "已有报告在生成中，请等待完成后再提交");
        boolean relationship = body != null && RELATIONSHIP_REPORT_VERSION.equals(string(body, "reportVersion"));
        if (relationship && runtime != null && !runtime.relationshipEnabled())
            throw new AuthException(403, "关系探索报告暂未开放");
        Map<String, Object> profile = relationship ? validateRelationship(body) : validate(body);
        if (!relationship) MatchmakingBenchmarks.context(string(profile, "city"));
        boolean imageOn = Boolean.TRUE.equals(profile.get("includePartnerImage"));
        if (queue.remainingCapacity() == 0) throw new AuthException(503, "生成队列已满，请稍后再试");
        boolean trial = user.isMatchmakingTrial();
        String taskId = UUID.randomUUID().toString();
        MatchmakingTask task = transactions.execute(status -> {
            int cost = 0;
            long transactionId = 0;
            if (trial) {
                if (trials == null) throw new AuthException(503, "婚恋内测服务暂不可用");
                trials.reserveTask(user.id(), taskId);
            } else {
                cost = quota.matchmakingCreditPerReport() + (imageOn ? quota.imageCredit("medium") : 0);
                transactionId = quota.spend(user.id(), cost, "MATCHMAKING_REPORT", taskId, "婚恋个人报告生成");
            }
            Map<String, Object> request = new LinkedHashMap<>(profile);
            if (relationship) {
                if (tarot == null) throw new AuthException(503, "塔罗牌阵服务暂不可用");
                request.put("tarot", tarot.consume(user.id(), string(profile, "drawId"), taskId));
            }
            MatchmakingTask created = new MatchmakingTask(taskId, user.id(), transactionId, cost, trial, imageOn,
                    relationship ? RELATIONSHIP_REPORT_VERSION : REPORT_VERSION,
                    relationship ? string(profile, "drawId") : "", request);
            insertTask(created);
            return created;
        });
        tasks.put(task.id, task);
        queue.add(task); // Admission is serialized, and the worker can only free capacity.
        return Map.of("taskId", task.id, "credits", trial ? 0 : quota.balance(user.id()), "reportVersion", task.reportVersion);
        }
    }

    public synchronized Map<String, Object> task(AuthUser user, String taskId) {
        MatchmakingTask task = tasks.get(taskId);
        if (task == null || task.userId != user.id()) throw new AuthException(404, "任务不存在");
        return task.view();
    }

    public synchronized List<Map<String, Object>> tasks(AuthUser user) {
        return tasks.values().stream().filter(t -> t.userId == user.id())
                .sorted(Comparator.comparing((MatchmakingTask t) -> t.createdAt).reversed())
                .limit(MAX_LISTED_TASKS).map(MatchmakingTask::view).toList();
    }

    public List<Map<String, Object>> reportSummaries(AuthUser user) {
        cleanExpired();
        String select = """
                SELECT r.id, r.user_id, u.username, tc.code_suffix, r.city, r.total_score, r.level, r.has_image,
                       r.report_version, r.title, r.personality_label, r.created_at
                FROM matchmaking_reports r JOIN users u ON u.id=r.user_id
                LEFT JOIN matchmaking_trial_codes tc ON tc.guest_user_id=r.user_id
                """;
        List<Map<String, Object>> rows = user.isRoot()
                ? jdbc.queryForList(select + " WHERE r.expires_at>CURRENT_TIMESTAMP ORDER BY r.created_at DESC LIMIT ?",
                    matchmakingMaxGlobalHistory())
                : jdbc.queryForList(select + " WHERE r.user_id=? AND r.expires_at>CURRENT_TIMESTAMP ORDER BY r.created_at DESC LIMIT ?",
                    user.id(), matchmakingMaxHistory());
        return rows.stream()
                .map(row -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("id", row.get("id")); summary.put("city", row.get("city"));
                    summary.put("total", row.get("total_score")); summary.put("level", row.get("level"));
                    summary.put("reportVersion", row.get("report_version")); summary.put("title", row.get("title"));
                    summary.put("personalityLabel", row.get("personality_label"));
                    String ownerLabel = ownerLabel(row);
                    summary.put("ownerUsername", ownerLabel); summary.put("ownerLabel", ownerLabel);
                    summary.put("viewerCanDelete", ((Number) row.get("user_id")).longValue() == user.id());
                    summary.put("hasImage", Boolean.TRUE.equals(row.get("has_image")) || Integer.valueOf(1).equals(row.get("has_image")));
                    Object createdAt = row.get("created_at");
                    summary.put("createdAt", createdAt instanceof Timestamp t ? t.toInstant().toString() : String.valueOf(createdAt));
                    return summary;
                }).toList();
    }

    public Map<String, Object> report(AuthUser user, String reportId) {
        cleanExpired();
        String select = """
                SELECT r.report_payload, r.user_id, u.username, tc.code_suffix
                FROM matchmaking_reports r JOIN users u ON u.id=r.user_id
                LEFT JOIN matchmaking_trial_codes tc ON tc.guest_user_id=r.user_id
                WHERE r.id=? AND r.expires_at>CURRENT_TIMESTAMP
                """;
        List<Map<String, Object>> records = user.isRoot()
                ? jdbc.queryForList(select, reportId)
                : jdbc.queryForList(select + " AND r.user_id=?", reportId, user.id());
        if (records.isEmpty()) throw new AuthException(404, "报告不存在或已过期");
        try {
            Map<String, Object> record = records.get(0);
            Map<String, Object> report = mapper.readValue(String.valueOf(record.get("report_payload")), new TypeReference<Map<String, Object>>() {});
            String ownerLabel = ownerLabel(record);
            report.put("ownerUsername", ownerLabel); report.put("ownerLabel", ownerLabel);
            report.put("viewerCanDelete", ((Number) record.get("user_id")).longValue() == user.id());
            return report;
        }
        catch (Exception e) { throw new AuthException(500, "已保存报告无法读取，请删除后重新生成"); }
    }

    public synchronized Map<String, Object> deleteReport(AuthUser user, String reportId) {
        List<MatchmakingTask> associated = tasks.values().stream()
                .filter(t -> t.userId == user.id() && reportId.equals(t.reportId)).toList();
        transactions.executeWithoutResult(status -> {
            List<String> profiles = jdbc.queryForList("SELECT profile_id FROM matchmaking_reports WHERE id=? AND user_id=?",
                    String.class, reportId, user.id());
            if (profiles.isEmpty()) throw new AuthException(404, "报告不存在或已过期");
            jdbc.update("DELETE FROM matchmaking_reports WHERE id=? AND user_id=?", reportId, user.id());
            jdbc.update("DELETE FROM matchmaking_profiles WHERE id=? AND user_id=?", profiles.get(0), user.id());
            if (tarot != null) tarot.deleteForReport(reportId);
            for (MatchmakingTask task : associated) jdbc.update("DELETE FROM matchmaking_tasks WHERE id=?", task.id);
        });
        associated.forEach(t -> tasks.remove(t.id));
        return Map.of("deleted", true);
    }

    public Map<String, Object> latest(AuthUser user) {
        cleanExpired();
        List<String> records = jdbc.queryForList("SELECT report_payload FROM matchmaking_reports WHERE user_id=? AND expires_at>CURRENT_TIMESTAMP ORDER BY created_at DESC LIMIT 1", String.class, user.id());
        if (records.isEmpty()) return Map.of("report", "", "remainingDays", 0);
        try { return Map.of("report", mapper.readValue(records.get(0), new TypeReference<Map<String, Object>>() {}), "remainingDays", 30); }
        catch (Exception e) { throw new AuthException(500, "已保存报告无法读取，请删除后重新生成"); }
    }

    public synchronized Map<String, Object> deleteAll(AuthUser user) {
        List<MatchmakingTask> owned = tasks.values().stream().filter(t -> t.userId == user.id()).toList();
        transactions.executeWithoutResult(status -> {
            // Compensate before removing the only recovery evidence; failure rolls back the deletion.
            for (MatchmakingTask task : owned) {
                MatchmakingTask durable = durableTask(task.id);
                if (!terminal(durable.status) || durable.compensationPending) {
                    if (task.trial) trials.markRetryable(task.userId, task.id);
                    else if (task.transactionId > 0) quota.refund(task.transactionId, "婚恋资料删除退款");
                }
            }
            jdbc.update("DELETE FROM matchmaking_reports WHERE user_id=?", user.id());
            jdbc.update("DELETE FROM matchmaking_profiles WHERE user_id=?", user.id());
            jdbc.update("DELETE FROM matchmaking_tasks WHERE user_id=?", user.id());
            if (tarot != null) tarot.deleteForUser(user.id());
        });
        for (MatchmakingTask task : owned) {
            task.request = Map.of(); task.status = "error";
            queue.remove(task); tasks.remove(task.id);
        }
        return Map.of("deleted", true);
    }

    public Map<String, Object> status(AuthUser user) {
        if (user.isMatchmakingTrial()) {
            return Map.of("creditCost", 0, "credits", 0, "retentionDays", 30,
                    "imageLowCredits", 0, "imageMediumCredits", 0, "matchmakingTrial", true,
                    "relationshipEnabled", relationshipEnabled());
        }
        return Map.of("creditCost", quota.matchmakingCreditPerReport(), "credits", quota.balance(user.id()), "retentionDays", 30,
                "imageLowCredits", quota.imageCredit("low"), "imageMediumCredits", quota.imageCredit("medium"),
                "relationshipEnabled", relationshipEnabled());
    }
    private boolean relationshipEnabled() { return runtime != null && runtime.relationshipEnabled(); }
    private boolean isRelationshipTask(MatchmakingTask task) { return RELATIONSHIP_REPORT_VERSION.equals(task.reportVersion); }
    /** Runs independently of user traffic so the 30-day retention promise is enforceable. */
    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Shanghai")
    public void cleanExpired() {
        jdbc.update("DELETE FROM matchmaking_reports WHERE expires_at<=CURRENT_TIMESTAMP");
        jdbc.update("DELETE FROM matchmaking_profiles WHERE expires_at<=CURRENT_TIMESTAMP");
        if (tarot != null) tarot.cleanExpired();
        pruneReportsByConfiguredLimits();
    }

    void pruneReportsByConfiguredLimits() {
        List<Map<String, Object>> reports = jdbc.queryForList("""
                SELECT id, profile_id, user_id FROM matchmaking_reports
                WHERE expires_at>CURRENT_TIMESTAMP ORDER BY created_at DESC, id DESC
                """);
        int perUserLimit = matchmakingMaxHistory();
        int globalLimit = matchmakingMaxGlobalHistory();
        Map<Long, Integer> userCounts = new LinkedHashMap<>();
        List<Map<String, Object>> retained = new java.util.ArrayList<>();
        List<Map<String, Object>> removed = new java.util.ArrayList<>();
        for (Map<String, Object> report : reports) {
            long userId = ((Number) report.get("user_id")).longValue();
            int count = userCounts.getOrDefault(userId, 0);
            if (count >= perUserLimit) removed.add(report);
            else {
                userCounts.put(userId, count + 1);
                retained.add(report);
            }
        }
        if (retained.size() > globalLimit) {
            removed.addAll(retained.subList(globalLimit, retained.size()));
        }
        for (Map<String, Object> report : removed) {
            jdbc.update("DELETE FROM matchmaking_reports WHERE id=?", report.get("id"));
            jdbc.update("DELETE FROM matchmaking_profiles WHERE id=?", report.get("profile_id"));
            if (tarot != null) tarot.deleteForReport(String.valueOf(report.get("id")));
        }
    }

    private void pruneReportsQuietly() {
        try {
            pruneReportsByConfiguredLimits();
        } catch (Exception e) {
            log.warn("婚恋报告数量清理失败，保留本次报告: {}", e.getMessage());
        }
    }

    private int matchmakingMaxHistory() { return runtime == null ? 20 : runtime.matchmakingMaxHistory(); }
    private int matchmakingMaxGlobalHistory() { return runtime == null ? 200 : runtime.matchmakingMaxGlobalHistory(); }

    private String ownerLabel(Map<String, Object> row) {
        Object suffix = row.get("code_suffix");
        return suffix == null ? String.valueOf(row.get("username")) : "内测访客 · ****" + suffix;
    }

    Map<String, Object> validate(Map<String, Object> body) {
        if (body == null) throw new AuthException(400, "资料不能为空");
        String requestedVersion = string(body, "reportVersion");
        if (!requestedVersion.isBlank() && !REPORT_VERSION.equals(requestedVersion)
                && !RELATIONSHIP_REPORT_VERSION.equals(requestedVersion))
            throw new AuthException(400, "不支持的报告版本");
        if (RELATIONSHIP_REPORT_VERSION.equals(requestedVersion)) return validateRelationship(body);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("includePartnerImage", booleanValue(body.get("includePartnerImage"), false));
        p.put("city", required(body, "city", 24)); p.put("education", required(body, "education", 40));
        p.put("studyStatus", optional(body, "studyStatus", 40)); p.put("industry", required(body, "industry", 60));
        p.put("workYears", decimal(body, "workYears", 0, 50));
        p.put("gender", choice(body, "gender", GENDERS));
        p.put("age", optionalDecimal(body, "age", 16, 70));
        p.put("heightCm", optionalDecimal(body, "heightCm", 100, 250));
        p.put("appearanceSelf", choice(body, "appearanceSelf", APPEARANCE_SELF));
        p.put("maritalStatus", choice(body, "maritalStatus", MARITAL_STATUS));
        p.put("hukou", optional(body, "hukou", 24));
        p.put("jobType", choice(body, "jobType", JOB_TYPES));
        p.put("workIntensity", choice(body, "workIntensity", WORK_INTENSITY));
        p.put("incomeComposition", optional(body, "incomeComposition", 60));
        p.put("hasCar", choice(body, "hasCar", CAR_STATUS));
        p.put("onlyChild", choice(body, "onlyChild", ONLY_CHILD));
        p.put("parentsPension", choice(body, "parentsPension", PARENTS_PENSION));
        p.put("parentsHealth", choice(body, "parentsHealth", PARENTS_HEALTH));
        p.put("parentsSupport", choice(body, "parentsSupport", PARENTS_SUPPORT));
        p.put("bridePriceView", optional(body, "bridePriceView", 80));
        p.put("partnerExpectations", optional(body, "partnerExpectations", 200));
        p.put("smokingHabit", choice(body, "smokingHabit", SMOKING));
        p.put("drinkingHabit", choice(body, "drinkingHabit", DRINKING));
        p.put("cohabitationExpectation", choice(body, "cohabitationExpectation", COHABITATION));
        p.put("portraitGender", choice(body, "portraitGender", PORTRAIT_GENDER));
        p.put("portraitAgeBand", choice(body, "portraitAgeBand", PORTRAIT_AGE));
        p.put("portraitStyle", choice(body, "portraitStyle", PORTRAIT_STYLE));
        p.put("portraitHair", choice(body, "portraitHair", PORTRAIT_HAIR));
        p.put("portraitScene", choice(body, "portraitScene", PORTRAIT_SCENE));
        p.put("incomeBand", choice(body, "incomeBand", INCOME_BANDS));
        if (string(p, "incomeBand").isBlank()) throw new AuthException(400, "请填写收入区间");
        p.put("housingStatus", choice(body, "housingStatus", HOUSING_OPTIONS));
        p.put("personality", validatePersonality(body.get("personality")));
        for (String key : List.of("savings", "housingCost", "debtPayment", "familySupport")) p.put(key, money(body, key, 0, 20_000_000));
        return p;
    }

    private Map<String, Object> validateRelationship(Map<String, Object> body) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reportVersion", RELATIONSHIP_REPORT_VERSION);
        p.put("questionnaireVersion", exact(body, "questionnaireVersion", RelationshipQuestionnaire.QUESTIONNAIRE_VERSION));
        p.put("personalityVersion", RelationshipQuestionnaire.PERSONALITY_VERSION);
        p.put("relationshipStage", oneOf(body, "relationshipStage", RelationshipQuestionnaire.STAGES));
        p.put("explorationIntent", oneOf(body, "explorationIntent", RelationshipQuestionnaire.INTENTS));
        String mode = oneOf(body, "personalityMode", RelationshipQuestionnaire.MODES);
        p.put("personalityMode", mode);
        String declaredType = optional(body, "declaredType", 4).toUpperCase();
        if ("selfReported".equals(mode)) {
            if (!RelationshipQuestionnaire.DECLARED_TYPES.contains(declaredType)) throw new AuthException(400, "请选择有效的四字母人格类型");
            p.put("declaredType", declaredType);
        } else if (!declaredType.isBlank()) throw new AuthException(400, "当前人格路径不接收自填类型");
        p.put("personalityAnswers", validatePersonality28(body.get("personalityAnswers"), "questionnaire".equals(mode)));
        p.put("relationshipAnswers", validateRelationshipAnswers(body.get("relationshipAnswers")));
        p.put("personalNote", optional(body, "personalNote", 200));
        p.put("includePartnerImage", booleanValue(body.get("includePartnerImage"), false));
        Map<String, Object> partnerPreferences = validatePartnerPreferences(body.get("partnerPreferences"));
        p.put("partnerPreferences", partnerPreferences);
        p.put("portraitGender", partnerPreferences.getOrDefault("gender", ""));
        p.put("portraitAgeBand", partnerPreferences.getOrDefault("ageBand", ""));
        p.put("portraitStyle", partnerPreferences.getOrDefault("style", ""));
        p.put("portraitHair", partnerPreferences.getOrDefault("hair", ""));
        p.put("portraitScene", partnerPreferences.getOrDefault("scene", ""));
        p.put("lifeContext", validateLifeContext(body.get("lifeContext")));
        String drawId = optional(body, "drawId", 36);
        if (drawId.isBlank()) throw new AuthException(400, "请先完成三张抽牌");
        p.put("drawId", drawId);
        return p;
    }

    private String exact(Map<String, Object> body, String key, String expected) {
        String value = optional(body, key, 80);
        if (!expected.equals(value)) throw new AuthException(400, key + "版本不匹配");
        return value;
    }

    private String oneOf(Map<String, Object> body, String key, Set<String> allowed) {
        String value = optional(body, key, 80);
        if (!allowed.contains(value)) throw new AuthException(400, key + "选项无效");
        return value;
    }

    private Map<String, Object> validatePersonality28(Object raw, boolean required) {
        Map<String, Object> answers = new LinkedHashMap<>();
        if (raw == null) {
            if (required) throw new AuthException(400, "请完成 16 题关系人格倾向问卷");
            return answers;
        }
        if (!(raw instanceof Map<?, ?> values)) throw new AuthException(400, "人格问卷格式无效");
        if (!required && !values.isEmpty()) throw new AuthException(400, "当前人格路径不接收问卷答案");
        if (!required) return answers;
        Set<String> allowed = RelationshipQuestionnaire.PERSONALITY_QUESTIONS.stream()
                .map(RelationshipQuestionnaire.PersonalityQuestion::id).collect(java.util.stream.Collectors.toSet());
        for (Object key : values.keySet()) if (!allowed.contains(String.valueOf(key))) throw new AuthException(400, "人格问卷包含未知题目");
        for (RelationshipQuestionnaire.PersonalityQuestion question : RelationshipQuestionnaire.PERSONALITY_QUESTIONS) {
            Object value = values.get(question.id());
            if (value == null) throw new AuthException(400, "请完成 " + question.id() + "");
            int number = integer(value, question.id());
            if (number != 1 && number != 2) throw new AuthException(400, question.id() + "选项无效");
            answers.put(question.id(), number);
        }
        return answers;
    }

    private Map<String, Object> validateRelationshipAnswers(Object raw) {
        if (!(raw instanceof Map<?, ?> values)) throw new AuthException(400, "关系偏好问卷格式无效");
        Set<String> allowed = Set.copyOf(RelationshipQuestionnaire.RELATIONSHIP_IDS);
        for (Object key : values.keySet()) if (!allowed.contains(String.valueOf(key))) throw new AuthException(400, "关系偏好包含未知题目");
        Map<String, Object> answers = new LinkedHashMap<>();
        for (String id : RelationshipQuestionnaire.RELATIONSHIP_IDS) {
            Object value = values.get(id);
            if (value == null) throw new AuthException(400, "请完成 " + id);
            int number = integer(value, id);
            if (number < -2 || number > 2) throw new AuthException(400, id + "选项无效");
            answers.put(id, number);
        }
        return answers;
    }

    private Map<String, Object> validatePartnerPreferences(Object raw) {
        if (raw == null) return new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> values)) throw new AuthException(400, "伴侣偏好格式无效");
        Map<String, Set<String>> allowed = Map.of(
                "gender", Set.of("男", "女", "不指定"), "ageBand", Set.of("22-26岁", "27-31岁", "32-36岁", "36岁以上", "不指定"),
                "style", Set.of("温柔亲切", "干练知性", "阳光活力", "沉稳安静", "不指定"),
                "hair", Set.of("长发", "短发", "扎发或盘发", "不指定"),
                "scene", Set.of("日常休闲", "职业装", "咖啡馆约会", "户外自然", "不指定"));
        Map<String, Object> result = new LinkedHashMap<>();
        for (Object key : values.keySet()) if (!allowed.containsKey(String.valueOf(key))) throw new AuthException(400, "伴侣偏好包含未知字段");
        allowed.forEach((key, choices) -> {
            String value = values.get(key) == null ? "" : String.valueOf(values.get(key)).trim();
            if (!value.isBlank() && !choices.contains(value)) throw new AuthException(400, "伴侣偏好" + key + "无效");
            if (!value.isBlank()) result.put(key, value);
        });
        return result;
    }

    private Map<String, Object> validateLifeContext(Object raw) {
        if (raw == null) return new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> values)) throw new AuthException(400, "生活底稿格式无效");
        Set<String> allowed = Set.of("city", "workRhythm", "livingExpectation", "housingStatus", "cohabitationExpectation");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Object key : values.keySet()) if (!allowed.contains(String.valueOf(key))) throw new AuthException(400, "生活底稿包含未知字段");
        for (String key : allowed) {
            Object value = values.get(key);
            if (value == null) continue;
            String text = String.valueOf(value).trim();
            if (text.length() > 80) throw new AuthException(400, "生活底稿字段过长");
            if (!text.isBlank()) result.put(key, text);
        }
        return result;
    }

    private int integer(Object raw, String key) {
        if (raw instanceof Boolean) throw new AuthException(400, key + "数值无效");
        try {
            double number = Double.parseDouble(String.valueOf(raw));
            if (!Double.isFinite(number) || number != Math.rint(number)) throw new NumberFormatException();
            return (int) number;
        } catch (Exception e) { throw new AuthException(400, key + "数值无效"); }
    }
    private Map<String, Object> validatePersonality(Object raw) {
        Map<String, Object> answers = new LinkedHashMap<>();
        if (raw == null) return answers;
        if (!(raw instanceof Map<?, ?> body)) throw new AuthException(400, "性格问卷格式无效");
        for (MatchmakingPersonality.Question question : MatchmakingPersonality.QUESTIONS) {
            Object value = body.get(question.key());
            String answer = value == null ? "" : String.valueOf(value).trim();
            if (answer.isEmpty()) continue;
            if (!question.options().containsKey(answer)) throw new AuthException(400, "性格问卷" + question.key() + "选项无效");
            answers.put(question.key(), answer);
        }
        return answers;
    }
    private Map<String, Object> calculate(Map<String, Object> p) {
        String band = string(p, "incomeBand");
        double income = MatchmakingScoring.incomeMidpoint(band);
        double fixed = number(p,"housingCost") + number(p,"debtPayment") + number(p,"familySupport");
        double cashflow = income - fixed; double savings = number(p,"savings"); double months = cashflow <= 0 ? 0 : savings / Math.max(1, fixed);
        Map<String,Object> ledger = new LinkedHashMap<>();
        ledger.put("incomeBasis", band.isBlank() || "不愿透露".equals(band) ? "收入未透露，仅用储蓄与固定支出估算" : "按所选收入区间（" + band + "）估算");
        ledger.put("estimatedMonthlyIncome", round(income)); ledger.put("monthlyFixedCommitments", round(fixed)); ledger.put("monthlyFreeCashflow", round(cashflow));
        ledger.put("housingDebtRatio", income == 0 ? 0 : round((number(p,"housingCost") + number(p,"debtPayment")) / income * 100));
        ledger.put("savingsCoverageMonths", round(months));
        ledger.put("budgetSignal", cashflow < 0 ? "当前固定支出高于估算收入，先处理现金流" : "当前现金流为正，可按月持续积累储蓄");
        return ledger;
    }
    /** Deterministic, coarse market signals only; exact amounts never leave the service. */
    Map<String, Object> marketSignals(Map<String, Object> p, Map<String, Object> context) {
        double monthly = MatchmakingScoring.incomeMidpoint(string(p, "incomeBand"));
        double perCapita = context.get("disposableIncome") instanceof Number n ? n.doubleValue() : 0;
        double annual = monthly * 12;
        String incomePosition = annual <= 0 ? "未提供收入"
                : annual >= perCapita * 2 ? "显著高于所在省居民人均可支配收入"
                : annual >= perCapita * 1.15 ? "高于所在省居民人均可支配收入"
                : annual >= perCapita * 0.85 ? "接近所在省居民人均可支配收入"
                : "低于所在省居民人均可支配收入";
        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("incomePosition", incomePosition);
        signals.put("savingsBucket", number(p, "savings") <= 0 ? "暂无储蓄" : amountBucket(number(p, "savings")));
        signals.put("housingSignal", string(p, "housingStatus").isBlank() ? "未填写住房状态" : string(p, "housingStatus"));
        signals.put("carSignal", string(p, "hasCar").isBlank() ? "未填写车辆情况" : string(p, "hasCar"));
        return signals;
    }
    Map<String, Object> agentProfile(Map<String, Object> p, Map<String, Object> signals) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String key : List.of("gender", "age", "heightCm", "appearanceSelf", "maritalStatus", "hukou", "education", "studyStatus",
                "industry", "jobType", "workYears", "workIntensity", "incomeComposition", "onlyChild", "incomeBand",
                "parentsPension", "parentsHealth", "parentsSupport", "bridePriceView", "partnerExpectations",
                "smokingHabit", "drinkingHabit", "cohabitationExpectation",
                "portraitGender", "portraitAgeBand", "portraitStyle", "portraitHair", "portraitScene")) {
            Object value = p.get(key);
            if (value instanceof Number n ? n.doubleValue() > 0 : value != null && !String.valueOf(value).isBlank()) m.put(key, value);
        }
        Object personality = p.get("personality");
        if (personality instanceof Map<?, ?> answers && !answers.isEmpty()) m.put("personality", answers);
        m.put("incomePosition", signals.get("incomePosition"));
        m.put("savingsBucket", signals.get("savingsBucket"));
        m.put("housingSignal", signals.get("housingSignal"));
        m.put("carSignal", signals.get("carSignal"));
        return m;
    }
    private Map<String, Object> agentLedger(Map<String,Object> ledger) { return Map.of("incomeBasis", ledger.get("incomeBasis"), "cashflowDirection", (double) ledger.get("monthlyFreeCashflow") >= 0 ? "positive" : "negative", "housingDebtRatio", ledger.get("housingDebtRatio"), "savingsCoverageMonths", ledger.get("savingsCoverageMonths"), "budgetSignal", ledger.get("budgetSignal")); }
    private static String amountBucket(double value) {
        if (value < 100_000) return "十万以内";
        if (value < 300_000) return "十万到三十万";
        if (value < 1_000_000) return "三十万到一百万";
        return "一百万以上";
    }
    private Map<String,Object> safeProfile(Map<String,Object> p) {
        Map<String,Object> safe = new LinkedHashMap<>();
        for (String key : List.of("city", "gender", "age", "heightCm", "appearanceSelf", "maritalStatus", "hukou", "education", "studyStatus",
                "industry", "jobType", "workYears", "workIntensity", "housingStatus", "hasCar", "onlyChild", "incomeBand")) {
            safe.put(key, p.get(key));
        }
        return safe;
    }
    /** Reuses the shared safe Images client; any failure fails the whole task so no partial charge survives. */
    private String generatePartnerImage(Map<String, Object> profile, Map<String, Object> narrative)
            throws java.io.IOException, InterruptedException {
        Map<?, ?> portrait = narrative.get("partnerPortrait") instanceof Map<?, ?> m ? m : Map.of();
        Object rawDescription = portrait.get("portrait");
        String description = rawDescription == null ? "" : String.valueOf(rawDescription);
        if (description.length() > 200) description = description.substring(0, 200);
        StringBuilder subject = new StringBuilder(switch (string(profile, "portraitGender")) {
            case "女" -> "一位年轻女性";
            case "男" -> "一位年轻男性";
            default -> "一位年轻人";
        });
        for (String key : List.of("portraitAgeBand", "portraitStyle", "portraitHair")) {
            String value = string(profile, key);
            if (!value.isBlank() && !"不指定".equals(value)) subject.append("，").append(value);
        }
        StringBuilder prompt = new StringBuilder("生成一张竖版半身人像照片：").append(subject);
        String scene = string(profile, "portraitScene");
        if (!scene.isBlank() && !"不指定".equals(scene)) prompt.append("，").append(scene).append("场景");
        if (!description.isBlank()) prompt.append("。人物特征参考：").append(description);
        prompt.append("。要求：真实感AI人像摄影风格，面部清晰自然，自然肤色与柔和光线，背景轻微虚化，构图干净；")
                .append("画面中不要出现任何文字、水印、标志，不要出现可识别的真实人物或名人长相，仅生成虚构人物。");
        try { return Base64.getEncoder().encodeToString(imageClient.generate(prompt.toString(), "1024x1536", "medium")); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException("伴侣画像照片生成被中断", e); }
    }
    private String required(Map<String,Object> map,String key,int max) { String value=optional(map,key,max); if(value.isBlank()) throw new AuthException(400,"请填写"+key); return value; }
    private String optional(Map<String,Object> map,String key,int max) { String value=map.get(key)==null?"":map.get(key).toString().trim(); if(value.length()>max) throw new AuthException(400,key+"长度无效"); return value; }
    private String choice(Map<String,Object> map,String key,Set<String> allowed) { String value=optional(map,key,20); if(value.isEmpty()||allowed.contains(value)) return value; throw new AuthException(400,key+"选项无效"); }
    private double optionalDecimal(Map<String,Object> m,String k,double min,double max) { String raw=m.get(k)==null?"":m.get(k).toString().trim(); return raw.isBlank()?0:decimal(m,k,min,max); }
    private double money(Map<String,Object> m,String k,double min,double max) { return decimal(m,k,min,max); }
    private double decimal(Map<String,Object> m,String k,double min,double max) { try { double value=m.get(k)==null||m.get(k).toString().isBlank()?0:Double.parseDouble(m.get(k).toString()); if(!Double.isFinite(value)||value<min||value>max) throw new NumberFormatException(); return value; } catch(Exception e) { throw new AuthException(400,k+"数值无效"); } }
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
    private boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : (value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value)));
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).replaceAll("[\\r\\n]+", " ").trim(); }
    private String string(Map<String,Object> m,String key) { return m == null || m.get(key) == null ? "" : String.valueOf(m.get(key)); }
    private double number(Map<String,Object> m,String key) { return m.get(key) instanceof Number n ? n.doubleValue() : 0d; }
    private double round(double value) { return Math.round(value * 100d) / 100d; }
}
