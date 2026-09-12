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
        assertTrue(TarotDeck.CARDS.stream().allMatch(card -> !card.keywords().isEmpty() && !card.relationshipMeaning().isBlank()));
    }
}
