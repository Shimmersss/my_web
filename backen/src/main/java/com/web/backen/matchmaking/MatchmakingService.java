package com.web.backen.matchmaking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MatchmakingService {
    private static final Logger log = LoggerFactory.getLogger(MatchmakingService.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MatchmakingReportAgent agent;
    private final QuotaService quota;

    public MatchmakingService(JdbcTemplate jdbc, ObjectMapper mapper, MatchmakingReportAgent agent, QuotaService quota) {
        this.jdbc = jdbc; this.mapper = mapper; this.agent = agent; this.quota = quota;
    }

    public Map<String, Object> catalogue() { return MatchmakingBenchmarks.catalogue(); }

    @Transactional
    public Map<String, Object> create(AuthUser user, Map<String, Object> body) {
        cleanExpired();
        Map<String, Object> profile = validate(body);
        String reportId = UUID.randomUUID().toString();
        long transactionId = quota.spend(user.id(), quota.matchmakingCreditPerReport(), "MATCHMAKING_REPORT", reportId, "婚恋个人报告生成");
        try {
            Map<String, Object> context = MatchmakingBenchmarks.context(string(profile, "city"));
            Map<String, Object> ledger = calculate(profile);
            Map<String, Object> agentInput = Map.of(
                    "cityContext", context,
                    "educationAndCareer", Map.of("education", string(profile, "education"), "studyStatus", string(profile, "studyStatus"),
                            "industry", string(profile, "industry"), "workYears", number(profile, "workYears")),
                    "calculatedLedger", agentLedger(ledger));
            Map<String, Object> narrative = agent.write(agentInput);
            String profileId = UUID.randomUUID().toString();
            Instant expiry = Instant.now().plusSeconds(30L * 24 * 3600);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("id", reportId); report.put("profile", safeProfile(profile)); report.put("context", context); report.put("ledger", ledger);
            report.put("narrative", narrative); report.put("createdAt", Instant.now().toString()); report.put("expiresAt", expiry.toString());
            jdbc.update("INSERT INTO matchmaking_profiles(id,user_id,payload,expires_at) VALUES(?,?,?,?)", profileId, user.id(),
                    mapper.writeValueAsString(profile), Timestamp.from(expiry));
            jdbc.update("INSERT INTO matchmaking_reports(id,profile_id,user_id,report_payload,source_version,expires_at) VALUES(?,?,?,?,?,?)",
                    reportId, profileId, user.id(), mapper.writeValueAsString(report), MatchmakingBenchmarks.VERSION, Timestamp.from(expiry));
            return report;
        } catch (AuthException e) { quota.refund(transactionId, "婚恋报告生成失败退款"); throw e;
        } catch (Exception e) {
            quota.refund(transactionId, "婚恋报告生成失败退款");
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().replaceAll("[\\r\\n]+", " ");
            log.warn("婚恋报告生成失败: {}", message.substring(0, Math.min(180, message.length())));
            throw new AuthException(502, "专属报告暂时无法生成，请稍后重试");
        }
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
        return Map.of("deleted", true);
    }

    public Map<String, Object> status(AuthUser user) {
        return Map.of("creditCost", quota.matchmakingCreditPerReport(), "credits", quota.balance(user.id()), "retentionDays", 30);
    }
    /** Runs independently of user traffic so the 30-day retention promise is enforceable. */
    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Shanghai")
    public void cleanExpired() {
        jdbc.update("DELETE FROM matchmaking_reports WHERE expires_at<=CURRENT_TIMESTAMP");
        jdbc.update("DELETE FROM matchmaking_profiles WHERE expires_at<=CURRENT_TIMESTAMP");
    }

    private Map<String, Object> validate(Map<String, Object> body) {
        if (body == null) throw new AuthException(400, "资料不能为空");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("city", required(body, "city", 24)); p.put("education", required(body, "education", 40));
        p.put("studyStatus", optional(body, "studyStatus", 40)); p.put("industry", required(body, "industry", 60));
        p.put("workYears", decimal(body, "workYears", 0, 50));
        String mode = required(body, "incomeMode", 12); if (!List.of("range", "exact").contains(mode)) throw new AuthException(400, "收入模式无效");
        p.put("incomeMode", mode); p.put("monthlyIncome", money(body, "monthlyIncome", 0, 1_000_000));
        p.put("incomeBandLow", money(body, "incomeBandLow", 0, 1_000_000)); p.put("incomeBandHigh", money(body, "incomeBandHigh", 0, 1_000_000));
        if ("exact".equals(mode) && number(p, "monthlyIncome") <= 0) throw new AuthException(400, "请填写月收入");
        if ("range".equals(mode) && (number(p, "incomeBandLow") <= 0 || number(p, "incomeBandHigh") < number(p, "incomeBandLow"))) throw new AuthException(400, "收入区间无效");
        for (String key : List.of("savings", "housingCost", "debtPayment", "familySupport", "targetBudget")) p.put(key, money(body, key, 0, 20_000_000));
        p.put("timelineMonths", decimal(body, "timelineMonths", 1, 240)); p.put("housingStatus", optional(body, "housingStatus", 40));
        return p;
    }
    private Map<String, Object> calculate(Map<String, Object> p) {
        double income = "exact".equals(string(p,"incomeMode")) ? number(p,"monthlyIncome") : (number(p,"incomeBandLow") + number(p,"incomeBandHigh")) / 2;
        double fixed = number(p,"housingCost") + number(p,"debtPayment") + number(p,"familySupport");
        double cashflow = income - fixed; double savings = number(p,"savings"); double months = cashflow <= 0 ? 0 : savings / Math.max(1, fixed);
        double goal = number(p,"targetBudget"); double timeline = number(p,"timelineMonths"); double monthlyGap = Math.max(0, goal - savings) / timeline;
        Map<String,Object> ledger = new LinkedHashMap<>();
        ledger.put("incomeBasis", "exact".equals(string(p,"incomeMode")) ? "精确月收入" : "收入区间中位数");
        ledger.put("estimatedMonthlyIncome", round(income)); ledger.put("monthlyFixedCommitments", round(fixed)); ledger.put("monthlyFreeCashflow", round(cashflow));
        ledger.put("housingDebtRatio", income == 0 ? 0 : round((number(p,"housingCost") + number(p,"debtPayment")) / income * 100));
        ledger.put("savingsCoverageMonths", round(months)); ledger.put("targetMonthlyFunding", round(monthlyGap));
        ledger.put("budgetSignal", cashflow < 0 ? "当前固定支出高于估算收入，先处理现金流" : monthlyGap > cashflow ? "现有时间线需要调整目标、储蓄或收入预期" : "目标可在当前现金流假设下继续细化");
        return ledger;
    }
    private Map<String,Object> agentLedger(Map<String,Object> ledger) { return Map.of("incomeBasis", ledger.get("incomeBasis"), "cashflowDirection", (double) ledger.get("monthlyFreeCashflow") >= 0 ? "positive" : "negative", "housingDebtRatio", ledger.get("housingDebtRatio"), "savingsCoverageMonths", ledger.get("savingsCoverageMonths"), "budgetSignal", ledger.get("budgetSignal")); }
    private Map<String,Object> safeProfile(Map<String,Object> p) { return Map.of("city",p.get("city"),"education",p.get("education"),"studyStatus",p.get("studyStatus"),"industry",p.get("industry"),"workYears",p.get("workYears"),"housingStatus",p.get("housingStatus"),"incomeMode",p.get("incomeMode"),"timelineMonths",p.get("timelineMonths")); }
    private String required(Map<String,Object> map,String key,int max) { String value=optional(map,key,max); if(value.isBlank()) throw new AuthException(400,"请填写"+key); return value; }
    private String optional(Map<String,Object> map,String key,int max) { String value=map.get(key)==null?"":map.get(key).toString().trim(); if(value.length()>max) throw new AuthException(400,key+"长度无效"); return value; }
    private double money(Map<String,Object> m,String k,double min,double max) { return decimal(m,k,min,max); }
    private double decimal(Map<String,Object> m,String k,double min,double max) { try { double value=m.get(k)==null||m.get(k).toString().isBlank()?0:Double.parseDouble(m.get(k).toString()); if(!Double.isFinite(value)||value<min||value>max) throw new NumberFormatException(); return value; } catch(Exception e) { throw new AuthException(400,k+"数值无效"); } }
    private String string(Map<String,Object> m,String key) { return String.valueOf(m.getOrDefault(key,"")); }
    private double number(Map<String,Object> m,String key) { return ((Number)m.getOrDefault(key,0)).doubleValue(); }
    private double round(double value) { return Math.round(value * 100d) / 100d; }
}
