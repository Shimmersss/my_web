package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.ai.LlmClient;
import com.web.backen.settings.RuntimeConfigService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RelationshipReportAgentTest {
    @Test
    void acceptsTheVersionedContractAndChecksServerOwnedCards() throws Exception {
        LlmClient llm = mock(LlmClient.class);
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        when(runtime.matchmakingLlmUrl()).thenReturn("https://llm.example");
        when(runtime.matchmakingLlmKey()).thenReturn("secret");
        when(runtime.matchmakingLlmModel()).thenReturn("model");
        when(runtime.matchmakingLlmProtocol()).thenReturn("auto");
        String response = validJson();
        when(llm.completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), eq(10000)))
                .thenReturn(response);
        RelationshipReportAgent agent = new RelationshipReportAgent(llm, runtime, new ObjectMapper());

        Map<String, Object> input = input();
        Map<String, Object> result = agent.write(input);
        assertEquals(List.of("present", "shadow", "next"), ((List<?>) result.get("tarotReadings")).stream()
                .map(item -> ((Map<?, ?>) item).get("slot")).toList());
        assertEquals(5, ((List<?>) result.get("relationshipManual")).size());
        verify(llm).completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), eq(10000));
    }

    @Test
    void retriesWhenModelChangesTheServerOwnedCard() throws Exception {
        LlmClient llm = mock(LlmClient.class);
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        when(runtime.matchmakingLlmUrl()).thenReturn("https://llm.example");
        when(runtime.matchmakingLlmKey()).thenReturn("secret");
        when(runtime.matchmakingLlmModel()).thenReturn("model");
        when(runtime.matchmakingLlmProtocol()).thenReturn("auto");
        when(llm.completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), eq(10000)))
                .thenReturn(validJson().replace("major-02", "major-21"));
        RelationshipReportAgent agent = new RelationshipReportAgent(llm, runtime, new ObjectMapper());

        assertThrows(IllegalStateException.class, () -> agent.write(input()));
        verify(llm, times(3)).completeWithConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), eq(10000));
    }

    @Test void rejectsEvidenceMissingFromThisRequest() throws Exception {
        RelationshipReportAgent agent = new RelationshipReportAgent(null, null, new ObjectMapper());
        var parse = RelationshipReportAgent.class.getDeclaredMethod("parse", String.class, Map.class);
        parse.setAccessible(true);
        String invalid = validJson().replace("\"r01\"]", "\"p01\"]");
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> parse.invoke(agent, invalid, input()));
        Map<String, Object> withAnswer = new LinkedHashMap<>(input());
        withAnswer.put("personalityEvidence", List.of(Map.of("id", "p01", "answer", "实际回答")));
        assertNotNull(parse.invoke(agent, invalid, withAnswer));
    }

    private Map<String, Object> input() {
        List<Map<String, Object>> cards = List.of(
                TarotDeck.view(TarotDeck.byId("major-00"), "present"),
                TarotDeck.view(TarotDeck.byId("major-01"), "shadow"),
                TarotDeck.view(TarotDeck.byId("major-02"), "next"));
        return Map.of("titleCandidates", List.of("温柔的远行者"), "tarot", Map.of("cards", cards),
                "relationshipPreferences", RelationshipQuestionnaire.RELATIONSHIP_IDS.stream().map(id -> Map.of("id", id)).toList());
    }

    private String validJson() throws Exception {
        String text = "这是一段用于关系观察的具体文字，帮助你把感受、需要和可以尝试的表达放回真实场景中。";
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("identity", Map.of("titleId", "温柔的远行者", "headline", text, "introduction", text));
        root.put("tarotReadings", List.of(
                tarot("present", "major-00", text), tarot("shadow", "major-01", text), tarot("next", "major-02", text)));
        List<Map<String, Object>> manual = new ArrayList<>();
        for (String id : RelationshipQuestionnaire.RELATIONSHIP_IDS)
            manual.add(Map.of("dimensionId", id, "preference", text, "misunderstanding", text, "expression", text, "evidenceIds", List.of(id)));
        root.put("relationshipManual", manual);
        List<Map<String, Object>> patterns = new ArrayList<>();
        for (int i = 0; i < 3; i++) patterns.add(Map.of("trigger", text, "reaction", text, "misunderstanding", text, "alternative", text, "evidenceIds", List.of("r01")));
        root.put("recurringPatterns", patterns);
        root.put("attraction", Map.of("spark", text, "sustainable", text, "friction", text, "evidenceIds", List.of("r02")));
        root.put("nextSteps", Map.of("scripts", List.of(
                Map.of("scenario", "当你想确认彼此节奏时", "words", text, "explanation", text),
                Map.of("scenario", "当你需要一点空间时", "words", text, "explanation", text),
                Map.of("scenario", "当一次对话结束时", "words", text, "explanation", text)), "experiment", text));
        return new ObjectMapper().writeValueAsString(root);
    }

    private Map<String, Object> tarot(String slot, String cardId, String text) {
        return Map.of("slot", slot, "cardId", cardId, "interpretation", text, "evidenceIds", List.of("r01"));
    }
}
