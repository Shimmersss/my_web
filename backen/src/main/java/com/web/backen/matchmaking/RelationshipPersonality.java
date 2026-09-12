package com.web.backen.matchmaking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic summary for the fixed 28-item relationship tendency catalogue. */
final class RelationshipPersonality {
    private static final List<String> AXIS_ORDER = List.of("EI", "SN", "TF", "JP");
    private static final Map<String, String[]> AXES = Map.of(
            "EI", new String[]{"E", "I"}, "SN", new String[]{"S", "N"},
            "TF", new String[]{"T", "F"}, "JP", new String[]{"J", "P"});
    private static final Map<String, String> AXIS_NAMES = Map.of(
            "EI", "能量来源", "SN", "信息取向", "TF", "决策关注", "JP", "行动节奏");

    private RelationshipPersonality() {}

    static Map<String, Object> summarize(String mode, String declaredType, Map<String, Object> answers) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", mode);
        result.put("explanationSource", "本站关系人格倾向 · personality-original-16-v1；非官方 MBTI 测评、非诊断");
        if ("selfReported".equals(mode)) {
            result.put("declaredType", declaredType);
            result.put("tendencyCode", declaredType);
            result.put("label", "你自选的 " + declaredType + " 关系人格入口");
            return result;
        }
        if ("skip".equals(mode)) {
            result.put("tendencyCode", null);
            result.put("label", "跳过人格，从这次关系偏好观察自己");
            return result;
        }
        Map<String, Object> axes = new LinkedHashMap<>();
        StringBuilder code = new StringBuilder();
        for (String axis : AXIS_ORDER) {
            String[] sides = AXES.get(axis);
            int first = 0, second = 0;
            for (RelationshipQuestionnaire.PersonalityQuestion question : RelationshipQuestionnaire.PERSONALITY_QUESTIONS) {
                if (!axis.equals(question.dimension())) continue;
                Object answer = answers.get(question.id());
                if (number(answer) == 1) first++;
                if (number(answer) == 2) second++;
            }
            String dominant = first == second ? "X" : first > second ? sides[0] : sides[1];
            int delta = Math.abs(first - second);
            String strength = delta == 0 ? "均衡" : delta == 1 ? "轻微偏向" : delta <= 3 ? "偏向" : "明显偏向";
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("name", AXIS_NAMES.get(axis)); detail.put("left", sides[0]); detail.put("right", sides[1]);
            detail.put("leftCount", first); detail.put("rightCount", second); detail.put("dominant", dominant); detail.put("strength", strength);
            axes.put(axis, detail); code.append(dominant);
        }
        result.put("tendencyCode", code.toString());
        result.put("axes", axes);
        result.put("label", label(axes));
        return result;
    }

    private static String label(Map<String, Object> axes) {
        List<String> parts = new ArrayList<>();
        for (String axis : List.of("EI", "SN", "TF", "JP")) {
            Map<?, ?> detail = (Map<?, ?>) axes.get(axis);
            String left = String.valueOf(detail.get("left"));
            String right = String.valueOf(detail.get("right"));
            String dominant = String.valueOf(detail.get("dominant"));
            parts.add("均衡".equals(detail.get("strength")) ? left + "/" + right + "均衡" : dominant);
        }
        return String.join(" · ", parts);
    }

    private static int number(Object value) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return 0; }
    }
}
