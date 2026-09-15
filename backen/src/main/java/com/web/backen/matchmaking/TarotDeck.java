package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The server-owned 22-card major arcana deck used by relationship-exploration-v1. */
public final class TarotDeck {
    public static final String VERSION = "major-22-v1";
    static final List<Card> CARDS = load();

    private TarotDeck() {}

    private static List<Card> load() {
        try (InputStream input = TarotDeck.class.getResourceAsStream("/tarot-major-v1.json")) {
            if (input == null) throw new IllegalStateException("塔罗牌库资源缺失");
            JsonNode root = new ObjectMapper().readTree(input);
            if (!VERSION.equals(root.path("version").asText())) throw new IllegalStateException("塔罗牌库版本不匹配");
            List<Card> cards = new ArrayList<>();
            for (JsonNode node : root.withArray("cards")) {
                List<String> keywords = new ArrayList<>();
                node.withArray("keywordsZh").forEach(item -> keywords.add(item.asText()));
                cards.add(new Card(node.path("id").asText(), node.path("sourceId").asInt(), node.path("name").asText(),
                        List.copyOf(keywords), node.path("relationshipMeaningZh").asText(), node.path("dailyGuidanceZh").asText()));
            }
            if (cards.size() != 22 || cards.stream().anyMatch(card -> card.id().isBlank() || card.name().isBlank() || card.dailyGuidance().isBlank()))
                throw new IllegalStateException("塔罗牌库必须包含22张有效大牌");
            return List.copyOf(cards);
        } catch (IOException e) {
            throw new IllegalStateException("塔罗牌库读取失败", e);
        }
    }

    static Card byId(String id) {
        return CARDS.stream().filter(card -> card.id().equals(id)).findFirst().orElse(null);
    }

    static Map<String, Object> view(Card card, String slot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("slot", slot); result.put("cardId", card.id()); result.put("sourceId", card.sourceId()); result.put("name", card.name());
        result.put("orientation", "upright"); result.put("keywordsZh", card.keywords());
        result.put("relationshipMeaningZh", card.relationshipMeaning());
        return result;
    }

    public static List<Map<String, Object>> catalogue() {
        return CARDS.stream().map(TarotDeck::publicView).toList();
    }

    public static Map<String, Object> randomDailyCard(java.security.SecureRandom random) {
        Card card = CARDS.get(random.nextInt(CARDS.size()));
        Map<String, Object> result = new LinkedHashMap<>(publicView(card));
        result.remove("relationshipMeaningZh");
        return result;
    }

    public static Map<String, Object> dailyCard(String cardId) {
        Card card = byId(cardId);
        if (card == null) return null;
        Map<String, Object> result = new LinkedHashMap<>(publicView(card));
        result.remove("relationshipMeaningZh");
        return result;
    }

    private static Map<String, Object> publicView(Card card) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cardId", card.id()); result.put("sourceId", card.sourceId()); result.put("name", card.name());
        result.put("orientation", "upright"); result.put("keywordsZh", card.keywords());
        result.put("relationshipMeaningZh", card.relationshipMeaning()); result.put("dailyGuidanceZh", card.dailyGuidance());
        return result;
    }

    record Card(String id, int sourceId, String name, List<String> keywords, String relationshipMeaning, String dailyGuidance) {}
}
