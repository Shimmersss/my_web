package com.web.backen.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.config.LlmConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private static final String SYSTEM_PROMPT = """
            You are a professional academic paper translator specializing in translating English research papers to Simplified Chinese. Follow these rules:
            1. Preserve the original paragraph structure
            2. Keep technical terms accurate; if a term has no standard Chinese translation, keep the English in parentheses
            3. Maintain academic tone and formal register
            4. Do not add explanations, notes, or commentary
            5. Output ONLY the translated text
            """;

    private static final String CONTINUATION_PROMPT = "This is a partial paragraph. Translate it as a continuation:";

    private final RestClient llmRestClient;
    private final LlmConfig llmConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmService(RestClient llmRestClient, LlmConfig llmConfig) {
        this.llmRestClient = llmRestClient;
        this.llmConfig = llmConfig;
    }

    /**
     * 翻译一段文本，含重试逻辑
     */
    public String translate(String sourceText) {
        return translate(sourceText, false);
    }

    /**
     * 翻译一段文本（Anthropic Messages API 格式）
     * @param sourceText 源文本
     * @param isContinuation 是否为长段落的后续部分
     */
    public String translate(String sourceText, boolean isContinuation) {
        if (llmConfig.getApiKey() == null || llmConfig.getApiKey().isBlank()) {
            throw new IllegalStateException("LLM API Key 未配置，请在 .env.local 中设置 LLM_API_KEY");
        }

        String userMessage = isContinuation
                ? CONTINUATION_PROMPT + "\n\n" + sourceText
                : sourceText;

        // Anthropic Messages API 格式：system 是顶级字段，messages 只放用户消息
        Map<String, Object> requestBody = Map.of(
                "model", llmConfig.getModel(),
                "max_tokens", llmConfig.getMaxTokens(),
                "system", SYSTEM_PROMPT,
                "messages", List.of(
                        Map.of("role", "user", "content", userMessage)
                )
        );

        int maxRetries = 3;
        long delayMs = 1000;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String responseJson = llmRestClient.post()
                        .uri("/v1/messages")
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);

                return extractContent(responseJson);
            } catch (Exception e) {
                String msg = e.getMessage();
                // 不可重试的错误
                if (msg != null && (msg.contains("401") || msg.contains("403") || msg.contains("400"))) {
                    throw new RuntimeException("LLM API 调用失败: " + msg, e);
                }

                if (attempt < maxRetries) {
                    log.warn("LLM API 调用失败 (第{}次)，{}ms 后重试: {}", attempt + 1, delayMs, msg);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("翻译被中断", ie);
                    }
                    delayMs *= 2;
                } else {
                    throw new RuntimeException("LLM API 调用失败（已重试" + maxRetries + "次）: " + msg, e);
                }
            }
        }

        throw new RuntimeException("LLM API 调用失败: 未知错误");
    }

    /**
     * 从 Anthropic Messages API 响应中提取翻译内容
     * 响应格式：{"content": [{"type": "text", "text": "..."}], "stop_reason": "end_turn", ...}
     */
    private String extractContent(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode content = root.get("content");
            if (content != null && content.isArray() && !content.isEmpty()) {
                // Anthropic 返回 content 数组，取第一个 text 类型的块
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        String text = block.get("text").asText().trim();
                        if (!text.isEmpty()) {
                            return text;
                        }
                    }
                }
            }
            throw new RuntimeException("LLM 响应格式异常: " + responseJson.substring(0, Math.min(200, responseJson.length())));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析 LLM 响应失败: " + e.getMessage(), e);
        }
    }
}
