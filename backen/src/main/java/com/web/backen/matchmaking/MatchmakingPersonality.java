package com.web.backen.matchmaking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Eight-question self-report survey used by the deterministic personality dimension.
 * Option points encode common first-impression preferences of the matchmaking market only;
 * they are not a statement about the worth of any personality type.
 */
final class MatchmakingPersonality {
    record Question(String key, String prompt, LinkedHashMap<String, Double> options) {}

    static final List<Question> QUESTIONS = List.of(
            new Question("q1", "周末通常怎么过？", options("聚会活动不断", 2.0, "和少数好友小聚", 3.0, "多数时间独处", 2.0, "完全看心情", 2.0)),
            new Question("q2", "遇到矛盾通常怎么做？", options("当时就说开", 2.5, "冷静后再谈", 3.0, "先憋着以后再说", 1.5, "看对方态度", 2.0)),
            new Question("q3", "消费习惯更接近？", options("记账储蓄优先", 3.0, "有计划地消费", 2.5, "比较随性", 1.5, "花钱比较大方", 1.0)),
            new Question("q4", "作息规律吗？", options("规律早起", 3.0, "规律晚睡", 2.5, "不太规律", 1.5)),
            new Question("q5", "情绪状态如何？", options("很稳定", 3.0, "偶尔波动", 2.0, "波动比较大", 1.0)),
            new Question("q6", "对伴侣的家人？", options("主动亲近", 3.0, "保持礼貌和边界", 2.5, "容易觉得有压力", 1.5)),
            new Question("q7", "对婚姻承诺的态度？", options("期待稳定的承诺", 3.0, "顺其自然", 2.0, "想到就有点压力", 1.5)),
            new Question("q8", "家务分工的看法？", options("共同承担", 3.0, "希望对方多承担", 1.5, "各管各的", 2.0)));

    private MatchmakingPersonality() {}

    private static LinkedHashMap<String, Double> options(Object... pairs) {
        LinkedHashMap<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(String.valueOf(pairs[i]), (Double) pairs[i + 1]);
        return map;
    }

    static double points(String key, String answer) {
        for (Question question : QUESTIONS) {
            if (question.key().equals(key)) return question.options().getOrDefault(answer, 0d);
        }
        return 0d;
    }

    /** Deterministic three-axis label; unanswered axes degrade to 未测. */
    static String type(Map<?, ?> answers) {
        String social = label(answers, "q1", Map.of("聚会活动不断", "外向", "和少数好友小聚", "均衡", "多数时间独处", "内向", "完全看心情", "均衡"));
        String conflict = label(answers, "q2", Map.of("当时就说开", "直率", "冷静后再谈", "沉稳", "先憋着以后再说", "回避", "看对方态度", "随和"));
        String rhythm = label(answers, "q4", Map.of("规律早起", "规律", "规律晚睡", "规律", "不太规律", "自由"));
        if (social.equals("未测") && conflict.equals("未测") && rhythm.equals("未测")) return "未完成性格自评";
        return social + "·" + conflict + "·" + rhythm + "型";
    }

    private static String label(Map<?, ?> answers, String key, Map<String, String> labels) {
        Object value = answers.get(key);
        return value == null ? "未测" : labels.getOrDefault(String.valueOf(value), "未测");
    }
}
