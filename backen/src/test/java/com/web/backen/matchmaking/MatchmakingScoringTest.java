package com.web.backen.matchmaking;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Locks the deterministic five-dimension scoring rules, gender curves and level bands. */
class MatchmakingScoringTest {

    @Test void fullProfileReachesOneHundredAndPreferredLevel() {
        Map<String, Object> profile = profile();
        profile.put("incomeBand", "5万以上"); profile.put("savings", 1_200_000);
        profile.put("housingStatus", "自有房无贷款"); profile.put("hasCar", "有");
        profile.put("education", "博士及以上"); profile.put("jobType", "公务员"); profile.put("workIntensity", "965");
        profile.put("workYears", 5); profile.put("gender", "男"); profile.put("age", 29); profile.put("heightCm", 181);
        profile.put("appearanceSelf", "出众"); profile.put("onlyChild", "是");
        profile.put("parentsPension", "有稳定退休金"); profile.put("parentsHealth", "健康"); profile.put("parentsSupport", "资助购房+帮带娃均可");
        profile.put("personality", topAnswers());
        Map<String, Object> scores = MatchmakingScoring.score(profile);
        assertEquals(100d, scores.get("total"));
        assertEquals("优选档", scores.get("level"));
        assertEquals("均衡·沉稳·规律型", scores.get("personalityType"));
        java.util.List<?> dimensions = (java.util.List<?>) scores.get("dimensions");
        assertEquals(5, dimensions.size());
        for (int i = 0; i < MatchmakingScoring.DIMENSION_NAMES.size(); i++) {
            Map<?, ?> dimension = (Map<?, ?>) dimensions.get(i);
            assertEquals(MatchmakingScoring.DIMENSION_NAMES.get(i), dimension.get("name"));
            assertFalse(String.valueOf(dimension.get("basis")).isBlank());
        }
    }

    @Test void ageCurveIsGenderSpecificWithNeutralFallback() {
        assertEquals(12d, ageScore("男", 29));
        assertEquals(11d, ageScore("女", 29));
        assertEquals(12d, ageScore("女", 25));
        assertEquals(5d, ageScore("男", 60));
        assertEquals((12d + 11d) / 2, ageScore("不愿透露", 29));
        assertEquals(6d, ageScore("男", 0));
    }

    @Test void heightCurveIsGenderSpecific() {
        assertEquals(4d, heightScore("男", 180));
        assertEquals(3.5d, heightScore("男", 175));
        assertEquals(4d, heightScore("女", 168));
        assertEquals(3.5d, heightScore("女", 165));
        assertEquals(2d, heightScore("男", 165));
        assertEquals(4d, heightScore("不愿透露", 176));
        assertEquals(3d, heightScore("男", 0));
    }

    @Test void weakProfileFallsIntoImproveLevel() {
        Map<String, Object> profile = profile();
        profile.put("incomeBand", "5千以下");
        profile.put("housingStatus", "租住"); profile.put("hasCar", "无");
        profile.put("education", "大专"); profile.put("jobType", "个体经营"); profile.put("workIntensity", "996");
        profile.put("workYears", 0); profile.put("gender", "男"); profile.put("age", 60); profile.put("heightCm", 160);
        profile.put("appearanceSelf", "一般"); profile.put("onlyChild", "否");
        profile.put("parentsPension", "无"); profile.put("parentsHealth", "需要照顾"); profile.put("parentsSupport", "暂无支持");
        profile.put("personality", lowestAnswers());
        Map<String, Object> scores = MatchmakingScoring.score(profile);
        assertTrue((Double) scores.get("total") < 55);
        assertEquals("提升档", scores.get("level"));
    }

    @Test void incomeMidpointMappingFeedsLedger() {
        assertEquals(25000d, MatchmakingScoring.incomeMidpoint("2万-3万"));
        assertEquals(9000d, MatchmakingScoring.incomeMidpoint("8千-1万"));
        assertEquals(2500d, MatchmakingScoring.incomeMidpoint("3千以下"));
        assertEquals(0d, MatchmakingScoring.incomeMidpoint("不愿透露"));
        assertEquals(0d, MatchmakingScoring.incomeMidpoint(""));
    }

    private double ageScore(String gender, double age) {
        Map<String, Object> profile = profile();
        profile.put("gender", gender); profile.put("age", age); profile.put("heightCm", 0);
        return dimensionScore(profile, "年龄外形") - heightScore(gender, 0) - appearanceScore(profile);
    }
    private double heightScore(String gender, double heightCm) {
        Map<String, Object> profile = profile();
        profile.put("gender", gender); profile.put("heightCm", heightCm);
        return dimensionScore(profile, "年龄外形") - appearanceScore(profile) - (number(profile, "age") < 16 ? 6d
                : "男".equals(gender) ? maleAgeAt((int) number(profile, "age")) : "女".equals(gender) ? femaleAgeAt((int) number(profile, "age"))
                : (maleAgeAt((int) number(profile, "age")) + femaleAgeAt((int) number(profile, "age"))) / 2);
    }
    private double appearanceScore(Map<String, Object> profile) { return 2.5; }
    private double dimensionScore(Map<String, Object> profile, String name) {
        Map<String, Object> scores = MatchmakingScoring.score(profile);
        for (Object item : (java.util.List<?>) scores.get("dimensions")) {
            Map<?, ?> dimension = (Map<?, ?>) item;
            if (name.equals(dimension.get("name"))) return (Double) dimension.get("score");
        }
        throw new AssertionError(name + " missing");
    }
    private double maleAgeAt(int age) {
        if (age < 24) return 10; if (age < 29) return 11; if (age < 34) return 12; if (age < 37) return 11; if (age < 41) return 9; if (age < 46) return 7; return 5;
    }
    private double femaleAgeAt(int age) {
        if (age < 24) return 10; if (age < 28) return 12; if (age < 32) return 11; if (age < 35) return 9; if (age < 39) return 7; if (age < 44) return 5; return 4;
    }
    private double number(Map<String, Object> m, String key) { return m.get(key) instanceof Number n ? n.doubleValue() : 0d; }

    private Map<String, Object> profile() {
        Map<String, Object> p = new HashMap<>();
        p.put("incomeBand", ""); p.put("savings", 0); p.put("housingStatus", ""); p.put("hasCar", "");
        p.put("education", ""); p.put("jobType", ""); p.put("workIntensity", ""); p.put("workYears", 0);
        p.put("gender", ""); p.put("age", 0); p.put("heightCm", 0); p.put("appearanceSelf", "");
        p.put("onlyChild", ""); p.put("parentsPension", ""); p.put("parentsHealth", ""); p.put("parentsSupport", "");
        p.put("personality", new HashMap<String, Object>());
        return p;
    }
    private Map<String, Object> topAnswers() {
        Map<String, Object> answers = new HashMap<>();
        answers.put("q1", "和少数好友小聚"); answers.put("q2", "冷静后再谈"); answers.put("q3", "记账储蓄优先");
        answers.put("q4", "规律早起"); answers.put("q5", "很稳定"); answers.put("q6", "主动亲近");
        answers.put("q7", "期待稳定的承诺"); answers.put("q8", "共同承担");
        return answers;
    }
    private Map<String, Object> lowestAnswers() {
        Map<String, Object> answers = new HashMap<>();
        answers.put("q1", "聚会活动不断"); answers.put("q2", "先憋着以后再说"); answers.put("q3", "花钱比较大方");
        answers.put("q4", "不太规律"); answers.put("q5", "波动比较大"); answers.put("q6", "容易觉得有压力");
        answers.put("q7", "想到就有点压力"); answers.put("q8", "希望对方多承担");
        return answers;
    }
}
