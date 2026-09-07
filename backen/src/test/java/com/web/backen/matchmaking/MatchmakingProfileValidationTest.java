package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the v3 profile contract: income band, structured family selects and the personality survey. */
class MatchmakingProfileValidationTest {
    private final MatchmakingService service =
            new MatchmakingService(null, new ObjectMapper(), null, null, null, null, null, null,
                    "build/matchmaking-tasks-test");

    @Test void acceptsBandInputStructuredFamilyAndPersonalitySurvey() {
        Map<String, Object> body = baseBody();
        body.put("gender", "男"); body.put("age", 29); body.put("heightCm", 176);
        body.put("appearanceSelf", "中上"); body.put("maritalStatus", "未婚"); body.put("hukou", "浙江宁波");
        body.put("jobType", "民营大厂"); body.put("workIntensity", "996");
        body.put("incomeComposition", "工资+年终奖"); body.put("hasCar", "无"); body.put("onlyChild", "是");
        body.put("parentsPension", "有稳定退休金"); body.put("parentsHealth", "健康"); body.put("parentsSupport", "可资助购房");
        body.put("housingStatus", "租住"); body.put("bridePriceView", "按当地习俗协商");
        body.put("partnerExpectations", "希望对方工作稳定、能沟通");
        body.put("personality", personalityAnswers());
        Map<String, Object> profile = service.validate(body);
        assertEquals("2万-3万", profile.get("incomeBand"));
        assertEquals("租住", profile.get("housingStatus"));
        assertEquals("有稳定退休金", profile.get("parentsPension"));
        assertEquals(8, ((Map<?, ?>) profile.get("personality")).size());
        assertFalse(profile.containsKey("monthlyIncome"));
        assertFalse(profile.containsKey("incomeBandLow"));
        assertFalse(profile.containsKey("parentsSituation"));
        Map<String, Object> signals = service.marketSignals(profile, MatchmakingBenchmarks.context("上海"));
        Map<String, Object> agentProfile = service.agentProfile(profile, signals);
        assertEquals("2万-3万", agentProfile.get("incomeBand"));
        assertEquals("有稳定退休金", agentProfile.get("parentsPension"));
        assertFalse(agentProfile.containsKey("savings"));
        Map<String, Object> scores = MatchmakingScoring.score(profile);
        assertTrue(scores.containsKey("total"));
    }

    @Test void blankOptionalFieldsStillPassButIncomeBandIsRequired() {
        Map<String, Object> profile = service.validate(baseBody());
        assertTrue(((Map<?, ?>) profile.get("personality")).isEmpty());
        assertEquals("硕士", profile.get("education"));
        Map<String, Object> noBand = baseBody(); noBand.remove("incomeBand");
        assertThrows(AuthException.class, () -> service.validate(noBand));
    }

    @Test void rejectsInvalidChoicesAndSurveyAnswers() {
        Map<String, Object> badGender = baseBody(); badGender.put("gender", "外星人");
        assertThrows(AuthException.class, () -> service.validate(badGender));
        Map<String, Object> badBand = baseBody(); badBand.put("incomeBand", "月薪3万");
        assertThrows(AuthException.class, () -> service.validate(badBand));
        Map<String, Object> badPension = baseBody(); badPension.put("parentsPension", "很多钱");
        assertThrows(AuthException.class, () -> service.validate(badPension));
        Map<String, Object> badSurvey = baseBody(); badSurvey.put("personality", Map.of("q5", "石头一样"));
        assertThrows(AuthException.class, () -> service.validate(badSurvey));
        Map<String, Object> badSurveyShape = baseBody(); badSurveyShape.put("personality", "外向");
        assertThrows(AuthException.class, () -> service.validate(badSurveyShape));
        Map<String, Object> badAge = baseBody(); badAge.put("age", 10);
        assertThrows(AuthException.class, () -> service.validate(badAge));
    }

    @Test void marketSignalsBucketFinancialsAndNeverExposeExactAmounts() {
        Map<String, Object> profile = service.validate(baseBody());
        Map<String, Object> signals = service.marketSignals(profile, MatchmakingBenchmarks.context("上海"));
        // 2万-3万 band midpoint 25000*12=300000 vs Shanghai 91987*2=183974 -> 显著高于
        assertEquals("显著高于所在省居民人均可支配收入", signals.get("incomePosition"));
        assertEquals("十万到三十万", signals.get("savingsBucket"));
        assertEquals("未填写住房状态", signals.get("housingSignal"));
        String dumped = signals.toString();
        assertFalse(dumped.contains("150000"));
        assertFalse(dumped.contains("25000"));
        Map<String, Object> lower = new HashMap<>(baseBody());
        lower.put("incomeBand", "3千-5千"); lower.put("savings", 5000);
        Map<String, Object> lowerSignals = service.marketSignals(service.validate(lower), MatchmakingBenchmarks.context("上海"));
        assertEquals("低于所在省居民人均可支配收入", lowerSignals.get("incomePosition"));
    }

    private Map<String, Object> baseBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("city", "上海"); body.put("education", "硕士"); body.put("industry", "互联网");
        body.put("incomeBand", "2万-3万"); body.put("savings", 150000);
        return body;
    }

    private Map<String, Object> personalityAnswers() {
        Map<String, Object> answers = new HashMap<>();
        answers.put("q1", "和少数好友小聚"); answers.put("q2", "冷静后再谈"); answers.put("q3", "记账储蓄优先");
        answers.put("q4", "规律早起"); answers.put("q5", "很稳定"); answers.put("q6", "主动亲近");
        answers.put("q7", "期待稳定的承诺"); answers.put("q8", "共同承担");
        return answers;
    }
}
