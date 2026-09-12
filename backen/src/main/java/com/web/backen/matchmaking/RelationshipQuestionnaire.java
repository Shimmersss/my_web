package com.web.backen.matchmaking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned, server-owned input catalogue for the relationship exploration report. */
final class RelationshipQuestionnaire {
    static final String QUESTIONNAIRE_VERSION = "relationship-questions-v1";
    static final String PERSONALITY_VERSION = "personality-original-16-v1";
    static final Set<String> STAGES = Set.of("single", "gettingCloser", "inRelationship", "reflecting");
    static final Set<String> INTENTS = Set.of("understandSelf", "communicateBetter", "understandAttraction", "nextStep");
    static final Set<String> MODES = Set.of("selfReported", "questionnaire", "skip");
    static final Set<String> DECLARED_TYPES = Set.of(
            "ISTJ", "ISFJ", "INFJ", "INTJ", "ISTP", "ISFP", "INFP", "INTP",
            "ESTP", "ESFP", "ENFP", "ENTP", "ESTJ", "ESFJ", "ENFJ", "ENTJ");
    static final List<String> RELATIONSHIP_IDS = List.of("r01", "r02", "r03", "r04", "r05");
    static final List<RelationshipQuestion> RELATIONSHIP_QUESTIONS = List.of(
            new RelationshipQuestion("r01", "喜欢的表达", "常用行动表达在意", "常用语言表达在意"),
            new RelationshipQuestion("r02", "相处距离", "需要较多独立时间", "需要较多共同时间"),
            new RelationshipQuestion("r03", "靠近节奏", "慢慢熟悉更安心", "快速了解更自然"),
            new RelationshipQuestion("r04", "关系约定", "随相处逐渐形成共识", "较早说清期待与边界"),
            new RelationshipQuestion("r05", "冲突处理", "先暂停整理再沟通", "趁当下及时谈清楚"));

    /** Site-authored reflection prompts; not a validated psychological scale. */
    static final List<PersonalityQuestion> PERSONALITY_QUESTIONS = List.of(
            q("p01", "EI", "一次开心的见面结束后，你更期待？", "继续分享刚才的新发现", "独自回味之后再联系"),
            q("p02", "EI", "想讲清复杂感受时，你更需要？", "在对话中找到说法", "先独自整理再开口"),
            q("p03", "EI", "一起做陌生的手工时，你倾向？", "主动聊天让气氛活起来", "先专注手上的事再交流"),
            q("p04", "EI", "几天密集交流后，你更愿意？", "一起参加轻松活动", "各自休息一会儿"),
            q("p05", "SN", "讨论怎样改善相处，你更想？", "回顾一次具体对话", "想象理想相处的可能"),
            q("p06", "SN", "对方想换一种生活，你先问？", "日常安排会怎样变化", "这份愿望有什么意义"),
            q("p07", "SN", "挑选共同纪念物，你更在意？", "它记录的经历细节", "它象征的共同愿望"),
            q("p08", "SN", "听到含蓄表达时，你先看？", "实际说出的内容", "话语背后的可能关联"),
            q("p09", "TF", "两人的安排冲突时，你先梳理？", "方案的代价与可行性", "双方的感受与需要"),
            q("p10", "TF", "制定共同规则时，你更关注？", "不同情况下能否一致执行", "双方是否都感到被照顾"),
            q("p11", "TF", "回看一次误会，你先寻找？", "推断在哪一步偏离", "哪句话改变了彼此感受"),
            q("p12", "TF", "提出不同意见前，你先准备？", "判断依据和替代方案", "表达对关系的在意"),
            q("p13", "JP", "安排一起散步，你更愿意？", "先定起点和结束时间", "碰面后决定走到哪里"),
            q("p14", "JP", "周末突然空出半天，你更想？", "选定一件想完成的事", "留白看看当时的心情"),
            q("p15", "JP", "共同项目接近截止，你更偏好？", "提前定稿留出检查时间", "继续探索到收拢的时候"),
            q("p16", "JP", "见面地点尚未确定，你更舒服的做法？", "提前选定减少临时讨论", "保留选项到当天再决定"));

    private RelationshipQuestionnaire() {}

    private static PersonalityQuestion q(String id, String dimension, String prompt, String left, String right) {
        return new PersonalityQuestion(id, dimension, prompt, left, right);
    }

    static Map<String, Object> catalogue() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("questionnaireVersion", QUESTIONNAIRE_VERSION);
        result.put("personalityVersion", PERSONALITY_VERSION);
        result.put("relationshipQuestions", RELATIONSHIP_QUESTIONS.stream().map(RelationshipQuestion::view).toList());
        result.put("personalityQuestions", PERSONALITY_QUESTIONS.stream().map(PersonalityQuestion::view).toList());
        result.put("declaredTypes", DECLARED_TYPES);
        result.put("personalityModes", MODES);
        result.put("relationshipStages", STAGES);
        result.put("explorationIntents", INTENTS);
        result.put("titles", List.of(
                "温柔的远行者", "慢热的守灯人", "热烈的理想主义者", "清醒的浪漫主义者",
                "安静的同行者", "坦率的探索者", "细腻的倾听者", "自由的共创者",
                "坚定的照顾者", "好奇的靠近者", "从容的观察者", "真诚的筑梦者"));
        return result;
    }

    record RelationshipQuestion(String id, String name, String left, String right) {
        Map<String, Object> view() {
            return Map.of("id", id, "name", name, "left", left, "right", right, "values", List.of(-2, -1, 0, 1, 2));
        }
    }

    record PersonalityQuestion(String id, String dimension, String prompt, String left, String right) {
        Map<String, Object> view() {
            return Map.of("id", id, "dimension", dimension, "prompt", prompt,
                    "options", List.of(Map.of("value", 1, "label", left), Map.of("value", 2, "label", right)));
        }
    }
}
