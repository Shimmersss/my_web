package com.web.backen.matchmaking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.translate.LlmService;
import com.web.backen.auth.RuntimeConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MimoMatchmakingReportAgent implements MatchmakingReportAgent {
    private static final Logger log = LoggerFactory.getLogger(MimoMatchmakingReportAgent.class);
    private static final String SYSTEM = """
            你负责写简体中文的个人生活规划报告。只接收结构化、经用户同意的资料，确定性预算计算和官方聚合背景；不联网检索、不编造事实、不估算人的价格、不预测婚恋成功率、不推荐撮合对象、不排序任何个人或受保护特征，也不重复精确财务输入。
            你的最终输出必须从 { 开始并且只包含一个 JSON 对象，不能输出思考过程、解释、Markdown 或代码围栏。键必须且只能为：summary、educationCareerAdvice、nextActions、limitations。其中 nextActions 必须恰好是 3 条简短字符串。说明城市统计仅是背景，而不是个人基准。
            """;
    private final LlmService llm;
    private final RuntimeConfigService runtime;
    private final ObjectMapper mapper;

    public MimoMatchmakingReportAgent(LlmService llm, RuntimeConfigService runtime, ObjectMapper mapper) { this.llm = llm; this.runtime = runtime; this.mapper = mapper; }

    @Override public Map<String, Object> write(Map<String, Object> structuredInput) {
        try {
            String response = jsonObject(llm.completeWithConfig(runtime.matchmakingLlmUrl(), runtime.matchmakingLlmKey(),
                    runtime.matchmakingLlmModel(), runtime.matchmakingLlmProtocol(), SYSTEM,
                    mapper.writeValueAsString(structuredInput), 3000));
            Map<String, Object> raw = mapper.readValue(response, new TypeReference<>() {});
            String summary = text(raw.get("summary"), 800);
            String advice = text(raw.get("educationCareerAdvice"), 800);
            String limitations = text(raw.get("limitations"), 600);
            List<String> actions = raw.get("nextActions") instanceof List<?> list ? list.stream()
                    .map(value -> text(value, 180)).filter(value -> !value.isBlank()).limit(3).toList() : List.of();
            if (summary.isBlank() || advice.isBlank() || limitations.isBlank() || actions.size() != 3) {
                throw new IllegalArgumentException("报告结构不完整");
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("summary", summary); report.put("educationCareerAdvice", advice);
            report.put("nextActions", actions); report.put("limitations", limitations);
            return report;
        } catch (Exception e) {
            log.warn("Mimo 婚恋报告输出不可用: {}", concise(e));
            throw new IllegalStateException("Mimo 未能生成符合格式的报告，请稍后重试", e);
        }
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
