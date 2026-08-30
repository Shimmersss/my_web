package com.web.backen.matchmaking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.imagegen.OpenAiImageClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
public class MatchmakingService {
    private static final Logger log = LoggerFactory.getLogger(MatchmakingService.class);
    static final String REPORT_VERSION = "market-positioning-v3";
    private static final int QUEUE_CAPACITY = 3;
    private static final int MAX_LISTED_TASKS = 10;
    private static final int MAX_LISTED_REPORTS = 20;
    /** Terminal task snapshots only feed the "recent tasks" list; reports live in MySQL, so a day is enough. */
    private static final long TASK_RETENTION_MS = 24L * 3600 * 1000;
    private static final Set<String> GENDERS = Set.of("男", "女", "不愿透露");
    private static final Set<String> APPEARANCE_SELF = Set.of("一般", "中上", "出众", "不愿评价");
    private static final Set<String> MARITAL_STATUS = Set.of("未婚", "离异无子女", "离异有子女", "丧偶", "其他");
    private static final Set<String> JOB_TYPES = Set.of("公务员", "事业编", "国企", "外企", "民营大厂", "民营中小企业", "自由职业", "个体经营", "其他");
    private static final Set<String> WORK_INTENSITY = Set.of("965", "996", "大小周", "倒班", "经常出差", "自由安排");
    private static final Set<String> CAR_STATUS = Set.of("无", "有", "有贷款");
    private static final Set<String> ONLY_CHILD = Set.of("是", "否", "不愿透露");
    private static final Set<String> INCOME_BANDS = Set.of("5千以下", "5千-1万", "1-2万", "2-3万", "3-5万", "5万以上", "不愿透露");
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
    private final String storageDir;
    private final LinkedBlockingQueue<MatchmakingTask> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Map<String, MatchmakingTask> tasks = new ConcurrentHashMap<>();

    public MatchmakingService(JdbcTemplate jdbc, ObjectMapper mapper, MatchmakingReportAgent agent, QuotaService quota,
                              OpenAiImageClient imageClient, TransactionTemplate transactions,
                              @Value("${matchmaking.storage-dir:../.run/matchmaking-tasks}") String storageDir) {
        this.jdbc = jdbc; this.mapper = mapper; this.agent = agent; this.quota = quota;
        this.imageClient = imageClient; this.transactions = transactions; this.storageDir = storageDir;
    }

    @PostConstruct void init() {
        recoverInterruptedTasks();
        Thread worker = new Thread(this::workerLoop, "matchmaking-worker");
        worker.setDaemon(true);
        worker.start();
    }

    /** Tasks found queued/running after a restart can never finish; refund so no credit leaks. */
    private void recoverInterruptedTasks() {
        File dir = new File(storageDir);
        File[] leftovers = dir.listFiles((d, name) -> name.endsWith(".tmp"));
        if (leftovers != null) for (File leftover : leftovers) leftover.delete();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return;
        Instant cutoff = Instant.now().minusMillis(TASK_RETENTION_MS);
        for (File file : files) {
            try {
                Map<String, Object> snapshot = mapper.readValue(file, new TypeReference<Map<String, Object>>() {});
                MatchmakingTask task = MatchmakingTask.fromSnapshot(snapshot);
                if ("queued".equals(task.status) || "running".equals(task.status)) {
                    task.status = "error"; task.stage = "error"; task.error = "服务重启导致任务中断，积分已退还";
                    refundQuietly(task);
                    tasks.put(task.id, task);
                    persist(task);
                } else if (stale(task, cutoff)) {
                    file.delete();
                } else {
                    tasks.put(task.id, task);
                }
            } catch (Exception e) {
                log.warn("婚恋任务文件恢复失败: {}", file.getName());
            }
        }
    }

    private static boolean terminal(String status) { return "done".equals(status) || "error".equals(status); }

    private static boolean stale(MatchmakingTask task, Instant cutoff) {
        if (!terminal(task.status)) return false;
        try { return Instant.parse(task.updatedAt).isBefore(cutoff); }
        catch (Exception e) { return true; }
    }

    /** Keeps task files and the in-memory map bounded; terminal snapshots older than a day are progress artifacts only. */
    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Shanghai")
    public void pruneFinishedTasks() {
        Instant cutoff = Instant.now().minusMillis(TASK_RETENTION_MS);
        tasks.values().removeIf(t -> stale(t, cutoff));
        File[] files = new File(storageDir).listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return;
        for (File file : files) {
            try {
                MatchmakingTask task = MatchmakingTask.fromSnapshot(mapper.readValue(file, new TypeReference<Map<String, Object>>() {}));
                if (stale(task, cutoff)) file.delete();
            } catch (Exception e) { log.warn("婚恋任务文件清理跳过: {}", file.getName()); }
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
            }
        }
    }

    private void run(MatchmakingTask task) {
        try {
            setStage(task, "scoring");
            Map<String, Object> profile = validate(task.request);
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
                partnerImage = generatePartnerImage(narrative);
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
            if (!tasks.containsKey(task.id)) {
                refundQuietly(task);
                return;
            }
            transactions.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO matchmaking_profiles(id,user_id,payload,expires_at) VALUES(?,?,?,?)",
                        profileId, task.userId, profilePayload, Timestamp.from(expiry));
                jdbc.update("INSERT INTO matchmaking_reports(id,profile_id,user_id,report_payload,source_version,total_score,level,city,has_image,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        reportId, profileId, task.userId, reportPayload,
                        MatchmakingBenchmarks.VERSION, ((Number) scores.getOrDefault("total", 0)).doubleValue(),
                        String.valueOf(scores.getOrDefault("level", "")), string(profile, "city"), hasImage, Timestamp.from(expiry));
            });
            task.reportId = reportId; task.status = "done"; task.stage = "done";
            task.updatedAt = Instant.now().toString();
            persist(task);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().replaceAll("[\\r\\n]+", " ");
            log.warn("婚恋报告任务 {} 失败: {}", task.id, message.substring(0, Math.min(180, message.length())));
            task.status = "error"; task.stage = "error"; task.error = "报告生成失败，积分已退还，请调整后重试";
            task.updatedAt = Instant.now().toString();
            refundQuietly(task);
            if (tasks.containsKey(task.id)) persist(task);
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

    private void setStage(MatchmakingTask task, String stage) {
        task.status = "running"; task.stage = stage; task.updatedAt = Instant.now().toString();
        persist(task);
    }

    private void refundQuietly(MatchmakingTask task) {
        if (task.transactionId <= 0) return;
        long transactionId = task.transactionId;
        task.transactionId = 0;
        try { quota.refund(transactionId, "婚恋报告任务失败退款"); }
        catch (Exception e) { log.warn("婚恋任务 {} 退款失败", task.id); }
    }

    private void persist(MatchmakingTask task) {
        try {
            File dir = new File(storageDir);
            if (!dir.exists() && !dir.mkdirs()) return;
            File target = new File(dir, task.id + ".json");
            File tmp = new File(dir, task.id + ".json.tmp");
            mapper.writeValue(tmp, task.snapshot());
            try { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            log.warn("婚恋任务 {} 状态保存失败", task.id);
        }
    }

    public Map<String, Object> catalogue() { return MatchmakingBenchmarks.catalogue(); }

    @Transactional
    public Map<String, Object> createTask(AuthUser user, Map<String, Object> body) {
        cleanExpired();
        boolean busy = tasks.values().stream().anyMatch(t -> t.userId == user.id()
                && ("queued".equals(t.status) || "running".equals(t.status)));
        if (busy) throw new AuthException(429, "已有报告在生成中，请等待完成后再提交");
        Map<String, Object> profile = validate(body);
        MatchmakingBenchmarks.context(string(profile, "city"));
        boolean imageOn = Boolean.TRUE.equals(body.get("includePartnerImage"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("includePartnerImage")));
        int imageCredits = imageOn ? quota.imageCredit("low") : 0;
        String reportId = UUID.randomUUID().toString();
        long transactionId = quota.spend(user.id(), quota.matchmakingCreditPerReport() + imageCredits,
                "MATCHMAKING_REPORT", reportId, imageOn ? "婚恋个人报告生成（含伴侣画像插画）" : "婚恋个人报告生成");
        MatchmakingTask task = new MatchmakingTask(UUID.randomUUID().toString(), user.id(), transactionId,
                quota.matchmakingCreditPerReport() + imageCredits, imageOn, body);
        tasks.put(task.id, task);
        if (!queue.offer(task)) {
            tasks.remove(task.id);
            quota.refund(transactionId, "婚恋报告队列已满退款");
            throw new AuthException(503, "生成队列已满，请稍后再试");
        }
        persist(task);
        return Map.of("taskId", task.id, "credits", quota.balance(user.id()));
    }

    public Map<String, Object> task(AuthUser user, String taskId) {
        MatchmakingTask task = tasks.get(taskId);
        if (task == null || task.userId != user.id()) throw new AuthException(404, "任务不存在");
        return task.view();
    }

    public List<Map<String, Object>> tasks(AuthUser user) {
        return tasks.values().stream().filter(t -> t.userId == user.id())
                .sorted(Comparator.comparing((MatchmakingTask t) -> t.createdAt).reversed())
                .limit(MAX_LISTED_TASKS).map(MatchmakingTask::view).toList();
    }

    public List<Map<String, Object>> reportSummaries(AuthUser user) {
        cleanExpired();
        return jdbc.queryForList("""
                SELECT id, city, total_score, level, has_image, created_at FROM matchmaking_reports
                WHERE user_id=? AND expires_at>CURRENT_TIMESTAMP ORDER BY created_at DESC LIMIT ?
                """, user.id(), MAX_LISTED_REPORTS).stream()
                .map(row -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("id", row.get("id")); summary.put("city", row.get("city"));
                    summary.put("total", row.get("total_score")); summary.put("level", row.get("level"));
                    summary.put("hasImage", Boolean.TRUE.equals(row.get("has_image")) || Integer.valueOf(1).equals(row.get("has_image")));
                    Object createdAt = row.get("created_at");
                    summary.put("createdAt", createdAt instanceof Timestamp t ? t.toInstant().toString() : String.valueOf(createdAt));
                    return summary;
                }).toList();
    }

    public Map<String, Object> report(AuthUser user, String reportId) {
        cleanExpired();
        List<String> records = jdbc.queryForList(
                "SELECT report_payload FROM matchmaking_reports WHERE id=? AND user_id=? AND expires_at>CURRENT_TIMESTAMP",
                String.class, reportId, user.id());
        if (records.isEmpty()) throw new AuthException(404, "报告不存在或已过期");
        try { return mapper.readValue(records.get(0), new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw new AuthException(500, "已保存报告无法读取，请删除后重新生成"); }
    }

    public Map<String, Object> deleteReport(AuthUser user, String reportId) {
        int removed = jdbc.update("DELETE FROM matchmaking_reports WHERE id=? AND user_id=?", reportId, user.id());
        if (removed == 0) throw new AuthException(404, "报告不存在或已过期");
        return Map.of("deleted", true);
    }

    public Map<String, Object> latest(AuthUser user) {
        cleanExpired();
        List<String> records = jdbc.queryForList("SELECT report_payload FROM matchmaking_reports WHERE user_id=? AND expires_at>CURRENT_TIMESTAMP ORDER BY created_at DESC LIMIT 1", String.class, user.id());
        if (records.isEmpty()) return Map.of("report", "", "remainingDays", 0);
        try { return Map.of("report", mapper.readValue(records.get(0), new TypeReference<Map<String, Object>>() {}), "remainingDays", 30); }
        catch (Exception e) { throw new AuthException(500, "已保存报告无法读取，请删除后重新生成"); }
    }

    @Transactional public Map<String, Object> deleteAll(AuthUser user) {
        jdbc.update("DELETE FROM matchmaking_reports WHERE user_id=?", user.id());
        jdbc.update("DELETE FROM matchmaking_profiles WHERE user_id=?", user.id());
        tasks.values().stream().filter(t -> t.userId == user.id()).forEach(t -> {
            new File(storageDir, t.id + ".json").delete();
            tasks.remove(t.id);
        });
        return Map.of("deleted", true);
    }

    public Map<String, Object> status(AuthUser user) {
        return Map.of("creditCost", quota.matchmakingCreditPerReport(), "credits", quota.balance(user.id()), "retentionDays", 30,
                "imageLowCredits", quota.imageCredit("low"));
    }
    /** Runs independently of user traffic so the 30-day retention promise is enforceable. */
    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Shanghai")
    public void cleanExpired() {
        jdbc.update("DELETE FROM matchmaking_reports WHERE expires_at<=CURRENT_TIMESTAMP");
        jdbc.update("DELETE FROM matchmaking_profiles WHERE expires_at<=CURRENT_TIMESTAMP");
    }

    Map<String, Object> validate(Map<String, Object> body) {
        if (body == null) throw new AuthException(400, "资料不能为空");
        Map<String, Object> p = new LinkedHashMap<>();
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
        p.put("incomeBand", choice(body, "incomeBand", INCOME_BANDS));
        if (string(p, "incomeBand").isBlank()) throw new AuthException(400, "请填写收入区间");
        p.put("housingStatus", choice(body, "housingStatus", HOUSING_OPTIONS));
        p.put("personality", validatePersonality(body.get("personality")));
        for (String key : List.of("savings", "housingCost", "debtPayment", "familySupport")) p.put(key, money(body, key, 0, 20_000_000));
        return p;
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
                "parentsPension", "parentsHealth", "parentsSupport", "bridePriceView", "partnerExpectations")) {
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
    private String generatePartnerImage(Map<String, Object> narrative) throws java.io.IOException, InterruptedException {
        Map<?, ?> portrait = narrative.get("partnerPortrait") instanceof Map<?, ?> m ? m : Map.of();
        Object rawDescription = portrait.get("portrait");
        String description = rawDescription == null ? "" : String.valueOf(rawDescription);
        if (description.length() > 220) description = description.substring(0, 220);
        String prompt = "根据以下相亲定位报告的推荐伴侣画像，画一张温馨、正能量的扁平风格插画：" + description
                + "。画面要求：柔和暖色调、干净背景、两个人物的半身或剪影、氛围自然亲切；画面中不要出现任何文字、水印、标志，不要出现可识别的真实人物长相。";
        try { return Base64.getEncoder().encodeToString(imageClient.generate(prompt, "1024x1024", "low")); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException("伴侣画像插画生成被中断", e); }
    }
    private String required(Map<String,Object> map,String key,int max) { String value=optional(map,key,max); if(value.isBlank()) throw new AuthException(400,"请填写"+key); return value; }
    private String optional(Map<String,Object> map,String key,int max) { String value=map.get(key)==null?"":map.get(key).toString().trim(); if(value.length()>max) throw new AuthException(400,key+"长度无效"); return value; }
    private String choice(Map<String,Object> map,String key,Set<String> allowed) { String value=optional(map,key,20); if(value.isEmpty()||allowed.contains(value)) return value; throw new AuthException(400,key+"选项无效"); }
    private double optionalDecimal(Map<String,Object> m,String k,double min,double max) { String raw=m.get(k)==null?"":m.get(k).toString().trim(); return raw.isBlank()?0:decimal(m,k,min,max); }
    private double money(Map<String,Object> m,String k,double min,double max) { return decimal(m,k,min,max); }
    private double decimal(Map<String,Object> m,String k,double min,double max) { try { double value=m.get(k)==null||m.get(k).toString().isBlank()?0:Double.parseDouble(m.get(k).toString()); if(!Double.isFinite(value)||value<min||value>max) throw new NumberFormatException(); return value; } catch(Exception e) { throw new AuthException(400,k+"数值无效"); } }
    private String string(Map<String,Object> m,String key) { return String.valueOf(m.getOrDefault(key,"")); }
    private double number(Map<String,Object> m,String key) { return ((Number)m.getOrDefault(key,0)).doubleValue(); }
    private double round(double value) { return Math.round(value * 100d) / 100d; }
}
