package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.moonlit.MoonlitDeck;
import java.util.*;

/** Immutable source for form constraints and the report's original card-meaning snapshot. */
final class TarotReadingCatalogue {
    static final String VERSION = "tarot-reading-v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode ROOT = load();
    private TarotReadingCatalogue() {}

    private static JsonNode load() {
        try (var in = TarotReadingCatalogue.class.getResourceAsStream("/tarot-reading-v1.json")) {
            if (in == null) throw new IllegalStateException("占卜牌义库缺失");
            JsonNode root = MAPPER.readTree(in);
            if (!VERSION.equals(root.path("version").asText()) || !TarotDeck.VERSION.equals(root.path("deckVersion").asText())
                    || root.path("cards").size() != 22 || root.toString().matches("(?s).*(婚恋|相亲|伴侣|MBTI|人格|会客厅).*"))
                throw new IllegalStateException("占卜牌义库版本或内容无效");
            Set<String> ids = new HashSet<>();
            for (JsonNode card : root.path("cards")) {
                TarotDeck.Card legacy = TarotDeck.byId(card.path("cardId").asText());
                if (legacy == null || !ids.add(legacy.id()) || !legacy.name().equals(card.path("name").asText())
                        || legacy.sourceId() != card.path("sourceId").asInt()) throw new IllegalStateException("牌库身份不一致");
                for (String orientation : List.of("upright", "reversed")) {
                    JsonNode face = card.path(orientation);
                    if (face.path("keywords").size() < 3 || face.path("keywords").size() > 4
                            || !inRange(face.path("meaning").asText(),40,120) || !inRange(face.path("advice").asText(),10,40))
                        throw new IllegalStateException("正逆位牌义不完整");
                }
                for (int theme=1; theme<=5; theme++) {
                    Map<String,Object> variant = MoonlitDeck.byId(legacy.id()+"-theme-0"+theme);
                    if (variant == null || String.valueOf(variant.getOrDefault("meaningZh", "")).isBlank())
                        throw new IllegalStateException("主题寓意缺失");
                }
            }
            return root;
        } catch (Exception e) { throw new IllegalStateException("占卜牌义库加载失败", e); }
    }
    static void initialize() { /* triggers validation during application construction */ }
    static boolean inRange(String value, int min, int max) { int n=value.codePointCount(0,value.length()); return n>=min && n<=max; }
    static Map<String,Object> spread(String id) { return find("spreads","id",id); }
    static Map<String,Object> topic(String id) { return find("topics","id",id); }
    private static Map<String,Object> find(String collection,String key,String id) {
        for (JsonNode item : ROOT.path(collection)) if (item.path(key).asText().equals(id)) return MAPPER.convertValue(item,Map.class);
        throw new AuthException(400,"无效的"+collection);
    }
    static Map<String,Object> context(Map<String,Object> body) {
        if (body==null) throw new AuthException(400,"请填写占卜问题");
        String spreadId=text(body,"spreadId"), topicId=text(body,"topicId");
        spread(spreadId); topic(topicId);
        String question=text(body,"question");
        if (!inRange(question,4,120)) throw new AuthException(400,"问题须为4–120字");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("spreadId",spreadId);result.put("topicId",topicId);result.put("question",question);result.put("options",null);
        if ("choice".equals(spreadId)) {
            String a=text(body,"optionA"),b=text(body,"optionB");
            if (!inRange(a,1,24)||!inRange(b,1,24)||a.equals(b)) throw new AuthException(400,"请填写两个不同的选项，各不超过24字");
            result.put("options",Map.of("optionA",a,"optionB",b));
        }
        return result;
    }
    private static String text(Map<String,Object> map,String key) {
        Object value=map.get(key);
        if (value!=null && !(value instanceof String)) throw new AuthException(400,key+"须为文本");
        return value==null?"":((String)value).strip();
    }
    @SuppressWarnings("unchecked")
    static List<Map<String,Object>> positions(String id) { return (List<Map<String,Object>>)spread(id).get("positions"); }
    static List<Map<String,Object>> snapshot(Map<String,Object> draw) {
        List<Map<String,Object>> result=new ArrayList<>();
        for (Object raw : (List<?>)draw.get("cards")) {
            Map<String,Object> picked=MAPPER.convertValue(raw,Map.class);
            Map<String,Object> card=find("cards","cardId",String.valueOf(picked.get("cardId")));
            Map<String,Object> face=MAPPER.convertValue(card.get(String.valueOf(picked.get("orientation"))),Map.class);
            Map<String,Object> row=new LinkedHashMap<>(picked); row.putAll(face);
            row.put("themeMeaning",MoonlitDeck.byId(String.valueOf(picked.get("variantId"))).get("meaningZh"));
            result.add(row);
        }
        return result;
    }
}
