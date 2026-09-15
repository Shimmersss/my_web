package com.web.backen.matchmaking;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TarotDeckTest {
    @Test
    void resourceContainsExactlyTwentyTwoUniqueMajorCards() {
        assertEquals(22, TarotDeck.CARDS.size());
        assertEquals(22, new HashSet<>(TarotDeck.CARDS.stream().map(TarotDeck.Card::id).toList()).size());
        assertEquals(List.of("major-00", "major-01", "major-02"), TarotDeck.CARDS.subList(0, 3).stream().map(TarotDeck.Card::id).toList());
        assertEquals("愚者", TarotDeck.byId("major-00").name());
        assertTrue(TarotDeck.CARDS.stream().allMatch(card -> !card.keywords().isEmpty()
                && !card.relationshipMeaning().isBlank() && !card.dailyGuidance().isBlank()));
    }

    @Test
    void publicCatalogueContainsOnlyCompleteCardSummaries() {
        List<java.util.Map<String, Object>> catalogue = TarotDeck.catalogue();

        assertEquals(22, catalogue.size());
        assertTrue(catalogue.stream().allMatch(card -> String.valueOf(card.get("cardId")).matches("major-(0[0-9]|1[0-9]|2[01])")));
        assertTrue(catalogue.stream().allMatch(card -> !String.valueOf(card.get("name")).isBlank()
                && card.get("keywordsZh") instanceof List<?> keywords && !keywords.isEmpty()
                && !String.valueOf(card.get("relationshipMeaningZh")).isBlank()
                && !String.valueOf(card.get("dailyGuidanceZh")).isBlank()));
        assertTrue(catalogue.stream().noneMatch(card -> card.containsKey("userId") || card.containsKey("drawId")));
    }
}
