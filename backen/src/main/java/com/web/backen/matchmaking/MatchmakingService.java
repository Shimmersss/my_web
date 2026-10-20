package com.web.backen.matchmaking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.auth.RuntimeConfigService;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

@Service
@org.springframework.context.annotation.DependsOn("matchmakingSchemaMigration")
public class MatchmakingService {
    private static final Logger log = LoggerFactory.getLogger(MatchmakingService.class);
    static final String REPORT_VERSION = "market-positioning-v3";
    static final String RELATIONSHIP_REPORT_VERSION = "relationship-exploration-v1";
    private static final int QUEUE_CAPACITY = 3;
    private static final int MAX_LISTED_TASKS = 10;
    /** Terminal task snapshots only feed the "recent tasks" list; reports live in MySQL, so a day is enough. */
    private static final long TASK_RETENTION_MS = 24L * 3600 * 1000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MatchmakingReportAgent agent;
    private final QuotaService quota;
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
                              TransactionTemplate transactions,
                              RuntimeConfigService runtime,
                              MatchmakingTrialService trials, MatchmakingTarotService tarot,
                              @Value("${matchmaking.storage-dir:../.run/matchmaking-tasks}") String storageDir) {
        this.jdbc = jdbc; this.mapper = mapper; this.agent = agent; this.quota = quota;
        this.transactions = transactions != null ? transactions
                : jdbc == null ? null : new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())); this.runtime = runtime;
        this.trials = trials; this.tarot = tarot; this.storageDir = storageDir;
    }

    /** Compatibility constructor retained for focused legacy tests and callers. */
    public MatchmakingService(JdbcTemplate jdbc, ObjectMapper mapper, MatchmakingReportAgent agent, QuotaService quota,
                              TransactionTemplate transactions,
                              RuntimeConfigService runtime, MatchmakingTrialService trials, String storageDir) {
        this(jdbc, mapper, agent, quota, transactions, runtime, trials, null, storageDir);
    }

    @PostConstruct void init() {
        recoverInterruptedTasks();
        worker = new Thread(this::workerLoop, "matchmaking-worker");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy void shutdown() { if (worker != null) worker.interrupt(); }

    /** Import legacy snapshots before recovery; SQL becomes the sole source of truth. */
    private void recoverInterruptedTasks() {
        File[] files = new File(storageDir).listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) for (File file : files) {
            try {
                MatchmakingTask task = decode(mapper.readValue(file, new TypeReference<Map<String, Object>>() {}));
                transactions.executeWithoutResult(status -> {
                    Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_tasks WHERE id=?", Integer.class, task.id);
                    if (found == null || found == 0) insertTask(task);
                });
                Files.delete(file.toPath());
                Files.deleteIfExists(new File(file.getPath() + ".tmp").toPath());
            } catch (Exception e) { throw new IllegalStateException("占卜旧任务导入失败: " + file.getName(), e); }
        }
        for (String payload : jdbc.queryForList("SELECT payload FROM matchmaking_tasks", String.class)) {
            MatchmakingTask task;
            try { task = decode(mapper.readValue(payload, new TypeReference<Map<String, Object>>() {})); }
            catch (Exception e) { throw new IllegalStateException("占卜任务恢复失败", e); }
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
                catch (Exception e) { log.error("占卜任务 {} 补偿状态保存失败，下轮重试", task.id, e); }
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
            } catch (Exception e) { log.error("占卜 worker 任务处理异常", e); }
        }
    }

    private void run(MatchmakingTask task) {
        try {
            if (!TarotReadingCatalogue.VERSION.equals(task.reportVersion)) throw new IllegalStateException("旧版生成已停止");
            setStage(task, "preparing");
            Map<String,Object> draw = map(task.request.get("tarot"));
            Map<String,Object> input = new LinkedHashMap<>();
            input.put("spread", TarotReadingCatalogue.spread(string(draw,"spreadId")));
            input.put("topic", TarotReadingCatalogue.topic(string(draw,"topicId")));
            input.put("question", draw.get("question")); input.put("options", draw.get("options"));
            input.put("cards", TarotReadingCatalogue.snapshot(draw));
            setStage(task,"writing");
            Map<String,Object> reading = agent.write(input);
            TarotReadingAgent.validate(reading,input);
            setStage(task,"saving");
            String reportId=UUID.randomUUID().toString(),profileId=UUID.randomUUID().toString();
            Instant expiry=Instant.now().plusSeconds(30L*24*3600);
            Map<String,Object> report = new LinkedHashMap<>(input);
            report.put("id",reportId);report.put("reportVersion",TarotReadingCatalogue.VERSION);
            report.put("catalogueVersion",TarotReadingCatalogue.VERSION);report.put("deckVersion",TarotDeck.VERSION);
            report.put("reading",reading);report.put("createdAt",Instant.now().toString());report.put("expiresAt",expiry.toString());
            String profilePayload=mapper.writeValueAsString(draw),reportPayload=mapper.writeValueAsString(report);
            synchronized (this) {
                if (!tasks.containsKey(task.id) || !"running".equals(task.status)) return;
                MatchmakingTask completed=decode(task.snapshot());
                completed.reportId=reportId;completed.status="done";completed.stage="done";
                completed.updatedAt=Instant.now().toString();completed.request=Map.of();
                transactions.executeWithoutResult(status -> {
                    jdbc.update("INSERT INTO matchmaking_profiles(id,user_id,payload,expires_at) VALUES(?,?,?,?)",profileId,task.userId,profilePayload,Timestamp.from(expiry));
                    String subtitle=String.valueOf(map(input.get("spread")).get("name"))+" · "+map(input.get("topic")).get("label");
                    jdbc.update("""
                        INSERT INTO matchmaking_reports(id,profile_id,user_id,report_payload,source_version,report_version,title,personality_label,has_image,expires_at)
                        VALUES(?,?,?,?,?,?,?,?,FALSE,?)
                        """,reportId,profileId,task.userId,reportPayload,TarotReadingCatalogue.VERSION,TarotReadingCatalogue.VERSION,reading.get("title"),subtitle,Timestamp.from(expiry));
                    tarot.markReport(task.tarotDrawId,task.id,reportId);
                    if (task.trial) trials.markCompleted(task.userId,task.id,reportId);
                    persist(completed);
                });
                task.reportId=completed.reportId;task.status=completed.status;task.stage=completed.stage;
                task.updatedAt=completed.updatedAt;task.request=Map.of();
            }
            pruneReportsQuietly();
        } catch (Exception e) {
            log.warn("占卜报告任务 {} 未完成，进入状态核对与补偿",task.id);
            synchronized (this) {
                if (!tasks.containsKey(task.id) || "error".equals(task.status)) return;
                task.reportId="";task.status="error";task.stage="error";task.updatedAt=Instant.now().toString();
                task.compensationPending=true;settleFailure(task);
            }
        }
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
                else if (task.transactionId > 0) quota.refund(task.transactionId, "占卜报告任务失败退款");
                if (task.tarotDrawId != null && !task.tarotDrawId.isBlank() && tarot != null) tarot.restoreAfterFailure(task.tarotDrawId, task.id);
                task.compensationPending = false;
                task.error = task.trial ? "报告生成未完成，可免费重试" : "报告生成未完成，积分已退还";
                persist(task);
            });
        } catch (Exception e) {
            task.compensationPending = true;
            task.error = task.trial ? "正在恢复免费生成资格，请稍后重试" : "报告生成未完成，积分退还处理中";
            log.error("占卜任务 {} 补偿待重试", task.id, e);
            // The preceding transaction already preserved the pending state for restart/retry.
        }
    }

    private MatchmakingTask durableTask(String id) {
        String saved = jdbc.queryForObject("SELECT payload FROM matchmaking_tasks WHERE id=? FOR UPDATE", String.class, id);
        try { return decode(mapper.readValue(saved, new TypeReference<Map<String, Object>>() {})); }
        catch (Exception e) { throw new IllegalStateException("无法核对占卜任务状态", e); }
    }

    private String snapshot(MatchmakingTask task) {
        try { return mapper.writeValueAsString(task.snapshot()); }
        catch (Exception e) { throw new IllegalStateException("占卜任务序列化失败", e); }
    }

    private void insertTask(MatchmakingTask task) {
        jdbc.update("INSERT INTO matchmaking_tasks(id,user_id,payload) VALUES (?,?,?)", task.id, task.userId, snapshot(task));
    }

    private void persist(MatchmakingTask task) {
        if (jdbc.update("UPDATE matchmaking_tasks SET payload=? WHERE id=?", snapshot(task), task.id) != 1)
            throw new IllegalStateException("占卜任务记录不存在");
    }

    public Map<String,Object> drawTarot(AuthUser user,Map<String,Object> body) {
        return tarot.draw(user.id(),body);
    }
    public Map<String,Object> pickTarot(AuthUser user,String id,Object positions) {
        return tarot.picks(user.id(),id,positions);
    }
    public Map<String, Object> currentTarot(AuthUser user) {
        if (tarot == null) { Map<String, Object> empty = new LinkedHashMap<>(); empty.put("draw", null); return empty; }
        return tarot.current(user.id());
    }

    // Synchronize admission with deletion/finalization; the service intentionally has one worker instance.
    public synchronized Map<String, Object> createTask(AuthUser user, Map<String, Object> body) {
        String version=string(body,"reportVersion");
        if (version.isBlank() || REPORT_VERSION.equals(version) || RELATIONSHIP_REPORT_VERSION.equals(version))
            throw new AuthException(410,"旧版报告生成已停止");
        if (!TarotReadingCatalogue.VERSION.equals(version)) throw new AuthException(400,"未知报告版本");
        if (runtime==null || !runtime.tarotAiEnabled()) throw new AuthException(403,"AI深度解读暂未开放");
        cleanExpired();
        boolean busy=tasks.values().stream().anyMatch(t->t.userId==user.id() && (!terminal(t.status) || t.compensationPending));
        if (busy) throw new AuthException(user.isMatchmakingTrial()?409:429,"已有解读进行中或待补偿，请稍后再试");
        if (queue.remainingCapacity() == 0) throw new AuthException(503, "生成队列已满，请稍后再试");
        boolean trial = user.isMatchmakingTrial();
        String taskId = UUID.randomUUID().toString();
        MatchmakingTask task = transactions.execute(status -> {
            int cost = 0;
            long transactionId = 0;
            if (trial) {
                if (trials == null) throw new AuthException(503, "占卜内测服务暂不可用");
                trials.reserveTask(user.id(), taskId);
            } else {
                cost = quota.matchmakingCreditPerReport();
                transactionId = quota.spend(user.id(), cost, "MATCHMAKING_REPORT", taskId, "塔罗深度解读");
            }
            String drawId=string(body,"drawId");
            if (tarot==null) throw new AuthException(503,"塔罗牌阵服务暂不可用");
            Map<String,Object> request=Map.of("tarot",tarot.consume(user.id(),drawId,taskId));
            MatchmakingTask created=new MatchmakingTask(taskId,user.id(),transactionId,cost,trial,false,
                    TarotReadingCatalogue.VERSION,drawId,request);
            insertTask(created);
            return created;
        });
        tasks.put(task.id, task);
        queue.add(task); // Admission is serialized, and the worker can only free capacity.
        return Map.of("taskId", task.id, "credits", trial ? 0 : quota.balance(user.id()), "reportVersion", task.reportVersion);
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
        String select = reportSummarySelect();
        List<Map<String, Object>> rows = user.isRoot()
                ? jdbc.queryForList(select + " WHERE r.expires_at>CURRENT_TIMESTAMP ORDER BY r.created_at DESC LIMIT ?",
                    matchmakingMaxGlobalHistory())
                : jdbc.queryForList(select + " WHERE r.user_id=? AND r.expires_at>CURRENT_TIMESTAMP ORDER BY r.created_at DESC LIMIT ?",
                    user.id(), matchmakingMaxHistory());
        return rows.stream().map(row -> reportSummary(row, user)).toList();
    }

    public Map<String, Object> reportArchive(AuthUser user, int requestedPage, int requestedPageSize, String requestedOwner) {
        cleanExpired();
        int page = Math.max(1, requestedPage), pageSize = Math.max(1, Math.min(20, requestedPageSize));
        String owner = requestedOwner == null ? "" : requestedOwner.trim();

        String where;
        List<Object> parameters = new ArrayList<>();
        if (user.isRoot()) {
            where = " WHERE r.expires_at>CURRENT_TIMESTAMP";
            if (!owner.isEmpty()) {
                where += " AND LOWER(u.username) LIKE ?";
                parameters.add("%" + owner.toLowerCase(Locale.ROOT) + "%");
            }
        } else {
            where = " WHERE r.user_id=? AND r.expires_at>CURRENT_TIMESTAMP";
            parameters.add(user.id());
        }

        String countSql = "SELECT COUNT(*) FROM matchmaking_reports r JOIN users u ON u.id=r.user_id" + where;
        Long counted = jdbc.queryForObject(countSql, Long.class, parameters.toArray());
        long total = counted == null ? 0 : counted;
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        if (totalPages > 0) page = Math.min(page, totalPages);
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(pageSize); pageParameters.add((page - 1) * pageSize);
        List<Map<String, Object>> rows = jdbc.queryForList(
                reportSummarySelect() + where + " ORDER BY r.created_at DESC LIMIT ? OFFSET ?", pageParameters.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows.stream().map(row -> reportSummary(row, user)).toList());
        result.put("page", page); result.put("pageSize", pageSize); result.put("total", total); result.put("totalPages", totalPages);
        return result;
    }

    private String reportSummarySelect() {
        return """
                SELECT r.id, r.user_id, u.username, tc.code_suffix, r.city, r.total_score, r.level, r.has_image,
                       r.report_version, r.title, r.personality_label, r.created_at
                FROM matchmaking_reports r JOIN users u ON u.id=r.user_id
                LEFT JOIN matchmaking_trial_codes tc ON tc.guest_user_id=r.user_id
                """;
    }

    private Map<String, Object> reportSummary(Map<String, Object> row, AuthUser user) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", row.get("id")); summary.put("city", row.get("city"));
        summary.put("total", row.get("total_score")); summary.put("level", row.get("level"));
        summary.put("reportVersion", row.get("report_version")); summary.put("title", row.get("title"));
        summary.put("personalityLabel", row.get("personality_label"));
        summary.put("subtitle", row.get("personality_label"));
        String ownerLabel = ownerLabel(row);
        summary.put("ownerUsername", ownerLabel); summary.put("ownerLabel", ownerLabel);
        summary.put("viewerCanDelete", ((Number) row.get("user_id")).longValue() == user.id());
        summary.put("hasImage", Boolean.TRUE.equals(row.get("has_image")) || Integer.valueOf(1).equals(row.get("has_image")));
        Object createdAt = row.get("created_at");
        summary.put("createdAt", createdAt instanceof Timestamp t ? t.toInstant().toString() : String.valueOf(createdAt));
        return summary;
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
                    else if (task.transactionId > 0) quota.refund(task.transactionId, "占卜资料删除退款");
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

    public Map<String,Object> status(AuthUser user) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("creditCost",user.isMatchmakingTrial()?0:quota.matchmakingCreditPerReport());
        if (!user.isMatchmakingTrial()) result.put("credits",quota.balance(user.id()));
        result.put("retentionDays",30);result.put("drawsPerHour",12);result.put("matchmakingTrial",user.isMatchmakingTrial());
        result.put("tarotAiEnabled",runtime!=null && runtime.tarotAiEnabled());return result;
    }
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
            log.warn("占卜报告数量清理失败，保留本次报告: {}", e.getMessage());
        }
    }

    private int matchmakingMaxHistory() { return runtime == null ? 20 : runtime.matchmakingMaxHistory(); }
    private int matchmakingMaxGlobalHistory() { return runtime == null ? 200 : runtime.matchmakingMaxGlobalHistory(); }

    private String ownerLabel(Map<String, Object> row) {
        Object suffix = row.get("code_suffix");
        return suffix == null ? String.valueOf(row.get("username")) : "内测访客 · ****" + suffix;
    }

    private Map<String,Object> map(Object value) {
        if (!(value instanceof Map<?,?> raw)) return new LinkedHashMap<>();
        Map<String,Object> result=new LinkedHashMap<>();raw.forEach((key,item)->result.put(String.valueOf(key),item));return result;
    }
    private String string(Map<String,Object> m,String key) { return m==null || m.get(key)==null?"":String.valueOf(m.get(key)); }
}
