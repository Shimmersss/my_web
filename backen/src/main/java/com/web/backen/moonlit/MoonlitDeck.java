package com.web.backen.moonlit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.*;

/** Each visual theme is an independent collectible and daily guidance card. */
public final class MoonlitDeck {
    public static final String VERSION = "moonlit-110-v1";
    private static final List<Map<String,Object>> CARDS = load();
    private MoonlitDeck() {}
    private static List<Map<String,Object>> load() {
        try (InputStream in = MoonlitDeck.class.getResourceAsStream("/moonlit-variants-v1.json")) {
            if (in == null) throw new IllegalStateException("月下主题牌库缺失");
            Map<String,Object> doc = new ObjectMapper().readValue(in, new TypeReference<>() {});
            if (!VERSION.equals(doc.get("version"))) throw new IllegalStateException("月下牌库版本不匹配");
            @SuppressWarnings("unchecked") List<Map<String,Object>> cards=(List<Map<String,Object>>)doc.get("cards");
            if(cards.size()!=110 || cards.stream().map(c->c.get("variantId")).distinct().count()!=110
                || cards.stream().anyMatch(c->String.valueOf(c.getOrDefault("dailyGuidanceZh", "")).isBlank()))
                throw new IllegalStateException("月下牌库必须包含110个独立主题与寓意");
            return cards.stream().map(Map::copyOf).toList();
        } catch (Exception e) { throw new IllegalStateException("月下牌库读取失败",e); }
    }
    public static List<Map<String,Object>> catalogue() { return CARDS; }
    public static Map<String,Object> randomCard(SecureRandom random) { return new LinkedHashMap<>(CARDS.get(random.nextInt(CARDS.size()))); }
    public static Map<String,Object> byId(String id) {
        return CARDS.stream().filter(c->id.equals(c.get("variantId"))).findFirst().orElse(null);
    }
}
