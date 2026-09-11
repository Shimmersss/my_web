package com.web.backen.matchmaking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.ai.LlmClient;
import com.web.backen.settings.RuntimeConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MimoMatchmakingReportAgent implements MatchmakingReportAgent {
    private static final Logger log = LoggerFactory.getLogger(MimoMatchmakingReportAgent.class);
    private static final List<String> DIMENSIONS = MatchmakingScoring.DIMENSION_NAMES;
    private static final int FAQ_ITEMS = 6;
    private static final int MAX_TOKENS = 10000;
    private static final int MAX_ATTEMPTS = 3;
    private static final String SYSTEM = """
            你负责写简体中文的相亲市场定位报告。输入包含：官方聚合统计背景、用户自愿资料的分档信号、服务器按公开规则算好的五维分数（经济基础/学历职业/年龄外形/家庭支持/性格相处，含每维得分依据）与确定性现金流信号。
            规则：不联网检索、不编造事实；统计背景只是省级或全国聚合参考，不是个人基准；引用数字只能来自传入背景。
            严格遵守：
            - 分数已由服务器确定性计算，你不得产出、修改、复述或估算任何分数；正文里不得出现“总分/数字+分”这类表述（例如“经济基础85分”是违规的），分析只描述强弱与原因；
            - 不得输出成功率、配对概率、包成功、保证成婚等任何承诺；不得使用“身价”一词；
            - 不得推荐、撮合或筛选具体对象；推荐的伴侣画像可结合用户性别描述本地传统婚恋市场的常见画像（如年龄区间、条件区间、性格特征），但只描述群体特征，不指向任何具体的人；
            - 不得复述精确金额、姓名、住址、单位名称或联系方式；谈钱只用相对表述；
            - 语气诚实、尊重、可执行，不贩卖焦虑、不羞辱、不给虚假希望；评分描述的是相亲市场供需参考，不是个人价值评判。
            你的最终输出必须从 { 开始并且只包含一个 JSON 对象，不能输出思考过程、解释、Markdown 或代码围栏，全文合计不超过 4000 字，优先保证 JSON 完整闭合，宁短勿断。键必须且只能为：selfIntro、marketReading、faqPrep、dimensionAnalysis、partnerPortrait、channelStrategy、limitations，七个键缺一不可。其中：
            - selfIntro 是一段 150–250 字的详细自我介绍（媒人话术版，可直接复制发给介绍人或贴在个人资料中，覆盖条件、生活方式、性格与期待）；
            - marketReading 是对象，键必须且只能为 hardAssets、highlights、gaps、sensitivities，分别说明市场硬通货、亮点、短板和市场敏感点，每项一到两句；
            - faqPrep 是恰好 6 个对象的数组，每个对象键为 question、answer，覆盖介绍人和对方父母最可能问的问题，回答诚实、直接可用；
            - dimensionAnalysis 是恰好 5 个对象的数组，dimension 依次必须是 经济基础、学历职业、年龄外形、家庭支持、性格相处，每个对象键为 analysis（用 3–5 句结合该维得分依据详细分析当前情况与在本地市场的位置）和 actions（给出 2–3 条具体可执行做法，用分号分隔）；
            - partnerPortrait 是对象，键必须且只能为 portrait（180–350 字的详细推荐伴侣画像：条件区间、生活方式、性格特征与相处场景）和 whyMatch（详细说明匹配点与需要磨合的地方）；报告中的伴侣推荐（partnerPortrait）必须以理想伴侣问卷答案（性别、年龄段、气质、发型、场景）为基准，结合用户条件与性格类型展开；问卷留空的项才按用户条件自动推断；
            - channelStrategy 是对象，键必须且只能为 mainChannels（主攻渠道与具体打法）和 avoidPitfalls（避坑提醒）；
            - limitations 说明数据与边界局限，并提醒评分是市场供需参考、按公开规则计算、不代表个人价值。
            """;
    private static final String RETRY_SUFFIX = "\n上一次输出不符合合同被拒收：JSON 必须完整闭合到最后的 }，七个键缺一不可（尤其 limitations），每个值精简，不得输出 JSON 以外的任何内容。";
    private final LlmClient llm;
    private final RuntimeConfigService runtime;
    private final ObjectMapper mapper;

    public MimoMatchmakingReportAgent(LlmClient llm, RuntimeConfigService runtime, ObjectMapper mapper) { this.llm = llm; this.runtime = runtime; this.mapper = mapper; }

    @Override public Map<String, Object> write(Map<String, Object> structuredInput) {
        String payload;
        try { payload = mapper.writeValueAsString(structuredInput); }
        catch (Exception e) { throw new IllegalStateException("报告输入序列化失败", e); }
        String url = runtime.matchmakingLlmUrl(); String key = runtime.matchmakingLlmKey();
        String model = runtime.matchmakingLlmModel(); String protocol = runtime.matchmakingLlmProtocol();
        Exception last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                String response = llm.completeWithConfig(url, key, model, protocol,
                        attempt == 0 ? SYSTEM : SYSTEM + RETRY_SUFFIX, payload, MAX_TOKENS);
                return parse(response);
            } catch (Exception e) {
                last = e;
                log.warn("Mimo 婚恋报告第 {} 次输出不可用: {}", attempt + 1, concise(e));
            }
        }
        throw new IllegalStateException("Mimo 未能生成符合格式的报告，请稍后重试", last);
    }

    private Map<String, Object> parse(String response) {
        String json = jsonObject(response);
        Map<String, Object> raw;
        try { raw = mapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalArgumentException("Mimo 输出不是完整 JSON", e); }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("selfIntro", requiredText(raw.get("selfIntro"), 460, "selfIntro"));
        report.put("marketReading", marketReading(raw.get("marketReading")));
        report.put("faqPrep", faqPrep(raw.get("faqPrep")));
        report.put("dimensionAnalysis", dimensionAnalysis(raw.get("dimensionAnalysis")));
        report.put("partnerPortrait", partnerPortrait(raw.get("partnerPortrait")));
        report.put("channelStrategy", channelStrategy(raw.get("channelStrategy")));
        report.put("limitations", requiredText(raw.get("limitations"), 400, "limitations"));
        return report;
    }


    private Map<String, Object> marketReading(Object value) {
        Map<?, ?> raw = value instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Object> market = new LinkedHashMap<>();
        for (String key : List.of("hardAssets", "highlights", "gaps", "sensitivities")) {
            market.put(key, requiredText(raw.get(key), 180, "marketReading." + key));
        }
        return market;
    }

    /** Contract type is an object; a plain string (or a legacy toString blob) is normalised instead of leaking raw. */
    private Map<String, Object> channelStrategy(Object value) {
        Map<String, Object> channel = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> m) {
            channel.put("mainChannels", requiredText(m.get("mainChannels"), 240, "channelStrategy.mainChannels"));
            channel.put("avoidPitfalls", requiredText(m.get("avoidPitfalls"), 240, "channelStrategy.avoidPitfalls"));
            return channel;
        }
        String legacy = requiredText(value, 420, "channelStrategy");
        channel.put("mainChannels", legacy);
        channel.put("avoidPitfalls", "详见主攻渠道说明");
        return channel;
    }

    private List<Map<String, String>> faqPrep(Object value) {
        List<Map<String, String>> items = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> m)) continue;
                String question = text(m.get("question"), 80);
                String answer = text(m.get("answer"), 180);
                if (question.isBlank() || answer.isBlank()) continue;
                items.add(Map.of("question", question, "answer", answer));
            }
        }
        if (items.size() != FAQ_ITEMS) throw new IllegalArgumentException("faqPrep 必须恰好 " + FAQ_ITEMS + " 条");
        return items;
    }

    private List<Map<String, String>> dimensionAnalysis(Object value) {
        List<Map<String, String>> items = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> m)) continue;
                String dimension = text(m.get("dimension"), 12);
                String analysis = text(m.get("analysis"), 320);
                String actions = text(m.get("actions"), 220);
                if (dimension.isBlank() || analysis.isBlank() || actions.isBlank()) continue;
                items.add(Map.of("dimension", dimension, "analysis", analysis, "actions", actions));
            }
        }
        if (items.size() != DIMENSIONS.size()) throw new IllegalArgumentException("dimensionAnalysis 必须恰好 5 维");
        for (int i = 0; i < DIMENSIONS.size(); i++) {
            if (!DIMENSIONS.get(i).equals(items.get(i).get("dimension"))) throw new IllegalArgumentException("dimensionAnalysis 维度顺序无效");
        }
        return items;
    }

    private Map<String, Object> partnerPortrait(Object value) {
        Map<?, ?> raw = value instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Object> portrait = new LinkedHashMap<>();
        portrait.put("portrait", requiredText(raw.get("portrait"), 420, "partnerPortrait.portrait"));
        portrait.put("whyMatch", requiredText(raw.get("whyMatch"), 300, "partnerPortrait.whyMatch"));
        return portrait;
    }

    private String requiredText(Object value, int max, String field) {
        String result = text(value, max);
        if (result.isBlank()) throw new IllegalArgumentException("报告缺少 " + field);
        return result;
    }
    private String text(Object value, int max) {
        String result = value == null ? "" : value.toString().replaceAll("[\\r\\n]+", " ").trim();
        return result.length() > max ? result.substring(0, max) : result;
    }
    private String jsonObject(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        int first = text.indexOf('{'), last = text.lastIndexOf('}');
        if (first < 0 || last <= first) throw new IllegalArgumentException("Mimo 未返回 JSON 对象");
        return text.substring(first, last + 1);
    }
    private String concise(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().replaceAll("[\\r\\n]+", " ");
        return message.substring(0, Math.min(180, message.length()));
    }
}
