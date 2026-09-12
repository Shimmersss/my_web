package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RelationshipPersonalityTest {
    private final MatchmakingService service = new MatchmakingService(null, new ObjectMapper(), null, null, null, null, null, null,
            "build/matchmaking-relationship-test");

    @Test
    void questionnaireUsesTheFixedBinarySourceAndStableAxisOrder() {
        Map<String, Object> body = validBody();
        Map<String, Object> answers = new LinkedHashMap<>();
        RelationshipQuestionnaire.PERSONALITY_QUESTIONS.forEach(question -> answers.put(question.id(), 1));
        body.put("personalityMode", "questionnaire");
        body.put("personalityAnswers", answers);

        Map<String, Object> profile = service.validate(body);
        Map<String, Object> summary = RelationshipPersonality.summarize("questionnaire", "", (Map<String, Object>) profile.get("personalityAnswers"));
        assertEquals("ESTJ", summary.get("tendencyCode"));
        assertEquals(List.of("EI", "SN", "TF", "JP"), ((Map<?, ?>) summary.get("axes")).keySet().stream().toList());
        assertEquals("personality-original-16-v1", summary.get("explanationSource") instanceof String source && source.contains("personality-original-16-v1") ? "personality-original-16-v1" : "missing");
    }

    @Test
    void selfReportedAndSkippedPathsDoNotPretendToMeasure() {
        Map<String, Object> self = validBody();
        self.put("personalityMode", "selfReported"); self.put("declaredType", "infj");
        Map<String, Object> selfProfile = service.validate(self);
        Map<String, Object> selfSummary = RelationshipPersonality.summarize("selfReported", (String) selfProfile.get("declaredType"), Map.of());
        assertEquals("INFJ", selfSummary.get("tendencyCode"));
        assertNull(selfSummary.get("axes"));

        Map<String, Object> skipped = validBody();
        skipped.put("personalityAnswers", Map.of());
        Map<String, Object> skippedProfile = service.validate(skipped);
        Map<String, Object> skippedSummary = RelationshipPersonality.summarize("skip", "", (Map<String, Object>) skippedProfile.get("personalityAnswers"));
        assertNull(skippedSummary.get("tendencyCode"));
        assertTrue(String.valueOf(skippedSummary.get("label")).contains("跳过"));
    }

    @Test
    void nonQuestionnaireAnswersAndWrongValuesAreRejected() {
        Map<String, Object> self = validBody();
        self.put("personalityAnswers", Map.of("p01", 1));
        assertThrows(com.web.backen.auth.AuthException.class, () -> service.validate(self));

        Map<String, Object> questionnaire = validBody();
        questionnaire.put("personalityMode", "questionnaire");
        Map<String, Object> answers = new LinkedHashMap<>();
        RelationshipQuestionnaire.PERSONALITY_QUESTIONS.forEach(question -> answers.put(question.id(), 1));
        answers.put("p28", 3);
        questionnaire.put("personalityAnswers", answers);
        assertThrows(com.web.backen.auth.AuthException.class, () -> service.validate(questionnaire));
    }

    private Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reportVersion", MatchmakingService.RELATIONSHIP_REPORT_VERSION);
        body.put("questionnaireVersion", RelationshipQuestionnaire.QUESTIONNAIRE_VERSION);
        body.put("relationshipStage", "single"); body.put("explorationIntent", "understandSelf");
        body.put("personalityMode", "skip"); body.put("relationshipAnswers", Map.of("r01", 0, "r02", -1, "r03", 1, "r04", 0, "r05", 2));
        body.put("drawId", "00000000-0000-0000-0000-000000000001");
        return body;
    }
}
