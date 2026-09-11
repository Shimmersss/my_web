package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.RuntimeConfigService;
import com.web.backen.ai.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Locks the v3 positioning contract: five-dimension analysis, partner portrait and the red-line gates. */
class MimoMatchmakingReportAgentTest {
    private LlmClient llm;
    private MimoMatchmakingReportAgent agent;

    @BeforeEach void setUp() {
        llm = mock(LlmClient.class);
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        when(runtime.matchmakingLlmUrl()).thenReturn("https://mimo.example/v1");
        when(runtime.matchmakingLlmKey()).thenReturn("key");
        when(runtime.matchmakingLlmModel()).thenReturn("mimo-v2.5");
        when(runtime.matchmakingLlmProtocol()).thenReturn("auto");
        agent = new MimoMatchmakingReportAgent(llm, runtime, new ObjectMapper());
    }

    @Test void parsesValidPositioningContract() {
        when(llm.completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(validJson());
        Map<String, Object> report = agent.write(Map.of("cityContext", Map.of(), "profile", Map.of()));
        assertEquals(7, report.size());
        Map<?, ?> market = (Map<?, ?>) report.get("marketReading");
        assertEquals(4, market.size());
        assertEquals(6, ((java.util.List<?>) report.get("faqPrep")).size());
        java.util.List<?> dimensions = (java.util.List<?>) report.get("dimensionAnalysis");
        assertEquals(MatchmakingScoring.DIMENSION_NAMES,
                dimensions.stream().map(item -> ((Map<?, ?>) item).get("dimension")).toList());
        Map<?, ?> portrait = (Map<?, ?>) report.get("partnerPortrait");
        assertTrue(portrait.containsKey("portrait") && portrait.containsKey("whyMatch"));
        Map<?, ?> channel = (Map<?, ?>) report.get("channelStrategy");
        assertEquals("主攻线上与行业圈", channel.get("mainChannels"));
        assertEquals("避免急于求成", channel.get("avoidPitfalls"));
        verify(llm).completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), eq(10000));
    }

    @Test void normalisesLegacyStringChannelStrategy() {
        when(llm.completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(validJson().replace("\"channelStrategy\":{\"mainChannels\":\"主攻线上与行业圈\",\"avoidPitfalls\":\"避免急于求成\"}",
                        "\"channelStrategy\":\"亲友介绍为主\""));
        Map<?, ?> channel = (Map<?, ?>) agent.write(Map.of()).get("channelStrategy");
        assertEquals("亲友介绍为主", channel.get("mainChannels"));
        assertFalse(String.valueOf(channel.get("avoidPitfalls")).isBlank());
    }

    @Test void retriesOnceWithTighterInstructionAfterContractFailure() {
        when(llm.completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(validJson().replace("\"limitations\":\"统计仅为省级背景\"", ""))
                .thenReturn(validJson());
        Map<String, Object> report = agent.write(Map.of());
        assertEquals(7, report.size());
        verify(llm, times(2)).completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), eq(10000));
    }

    @Test void stripsMarkdownFenceAroundJson() {
        when(llm.completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn("```json\n" + validJson() + "\n```");
        assertEquals(5, ((java.util.List<?>) agent.write(Map.of()).get("dimensionAnalysis")).size());
    }

    @Test void acceptsWordingThatUsedToBeGated() {
        when(llm.completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(validJson()
                        .replace("坦诚沟通", "各平台成功率差异很大，不保证成功，给出综合评分的口径说明")
                        .replace("同域、生活规律、重视沟通的伴侣画像。", "避免用身价衡量，认真考虑结婚计划，等12分钟再联系。"));
        Map<String, Object> report = agent.write(Map.of());
        assertEquals(7, report.size());
    }

    @Test void allowsTimePhrasesWithoutFalsePositives() {
        when(llm.completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(validJson().replace("坦诚沟通", "建议等12分钟再联系，认真考虑结婚计划，给出综合评分的口径说明"));
        assertDoesNotThrow(() -> agent.write(Map.of()));
    }

    @Test void rejectsIncompleteContractBadDimensionOrderAndFaqCount() {
        when(llm.completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(validJson().replace("\"mainChannels\":\"主攻线上与行业圈\"", "\"mainChannels\":\"\""));
        assertThrows(IllegalStateException.class, () -> agent.write(Map.of()));
        when(llm.completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(validJson().replace("学历职业", "__TMP__").replace("性格相处", "学历职业").replace("__TMP__", "性格相处"));
        assertThrows(IllegalStateException.class, () -> agent.write(Map.of()));
        String fourFaq = validJson().replace(",{\"question\":\"问6\",\"answer\":\"答6\"}]", "]");
        when(llm.completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(fourFaq);
        assertThrows(IllegalStateException.class, () -> agent.write(Map.of()));
    }


    private String validJson() {
        StringBuilder faq = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            if (i > 1) faq.append(',');
            faq.append("{\"question\":\"问").append(i).append("\",\"answer\":\"答").append(i).append("\"}");
        }
        StringBuilder dims = new StringBuilder();
        for (int i = 0; i < MatchmakingScoring.DIMENSION_NAMES.size(); i++) {
            if (i > 0) dims.append(',');
            dims.append("{\"dimension\":\"").append(MatchmakingScoring.DIMENSION_NAMES.get(i))
                    .append("\",\"analysis\":\"情况").append(i).append("\",\"actions\":\"行动").append(i).append("\"}");
        }
        return """
                {"selfIntro":"硕士学历，工作稳定，有清晰储蓄计划。",
                 "marketReading":{"hardAssets":"工作稳定收入尚可","highlights":"学历与储蓄习惯","gaps":"住房尚未落实","sensitivities":"异地与工作时间"},
                 "faqPrep":[%s],
                 "dimensionAnalysis":[%s],
                 "partnerPortrait":{"portrait":"同域、生活规律、重视沟通的伴侣画像。","whyMatch":"双方目标一致，坦诚沟通即可"},
                 "channelStrategy":{"mainChannels":"主攻线上与行业圈","avoidPitfalls":"避免急于求成"},
                 "limitations":"统计仅为省级背景"}
                """.formatted(faq, dims);
    }
}
