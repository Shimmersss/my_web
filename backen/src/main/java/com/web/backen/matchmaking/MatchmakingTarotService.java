package com.web.backen.matchmaking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.moonlit.MoonlitDeck;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.*;

/** Server-owned concealed deck. User-row locking serializes draws, picks and report admission. */
@Service
public class MatchmakingTarotService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final SecureRandom random=new SecureRandom();
    public MatchmakingTarotService(JdbcTemplate jdbc,ObjectMapper mapper,TransactionTemplate transactions) {
        this.jdbc=jdbc;this.mapper=mapper;this.transactions=transactions;
        TarotReadingCatalogue.initialize();
    }
    public Map<String,Object> draw(long userId,Map<String,Object> body) {
        Map<String,Object> context=TarotReadingCatalogue.context(body);
        return transactions.execute(status -> {
            lockUser(userId);
            Integer pending=jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_tarot_draws WHERE user_id=? AND consumed_task_id IS NOT NULL AND consumed_report_id IS NULL",Integer.class,userId);
            if (pending!=null && pending>0) throw new AuthException(409,"当前牌阵正在解读，请等待完成或失败恢复");
            Integer recent=jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_tarot_draws WHERE user_id=? AND created_at>?",Integer.class,userId,Timestamp.from(Instant.now().minusSeconds(3600)));
            if (recent!=null && recent>=12) throw new AuthException(429,"抽牌过于频繁，请稍后再试");
            jdbc.update("UPDATE matchmaking_tarot_draws SET expires_at=CURRENT_TIMESTAMP WHERE user_id=? AND consumed_task_id IS NULL AND consumed_report_id IS NULL AND expires_at>CURRENT_TIMESTAMP",userId);
            List<TarotDeck.Card> deck=new ArrayList<>(TarotDeck.CARDS);
            Collections.shuffle(deck,random);
            List<Map<String,Object>> hidden=new ArrayList<>();
            for (TarotDeck.Card card : deck) {
                String themeId="theme-0"+(1+random.nextInt(5));
                Map<String,Object> variant=MoonlitDeck.byId(card.id()+"-"+themeId);
                hidden.add(Map.of("cardId",card.id(),"sourceId",card.sourceId(),"name",card.name(),
                    "orientation",random.nextBoolean()?"upright":"reversed","themeId",themeId,
                    "variantId",variant.get("variantId"),"themeName",variant.get("themeName")));
            }
            String id=UUID.randomUUID().toString();Instant now=Instant.now();
            jdbc.update("""
                INSERT INTO matchmaking_tarot_draws(id,user_id,deck_version,cards_json,spread_id,topic_id,reading_context,deck_order_json,created_at,expires_at)
                VALUES(?,?,?,'[]',?,?,?,?,?,?)
                """,id,userId,TarotDeck.VERSION,context.get("spreadId"),context.get("topicId"),json(context),json(hidden),Timestamp.from(now),Timestamp.from(now.plusSeconds(86400)));
            return decode(row(userId,id));
        });
    }
    public Map<String,Object> picks(long userId,String id,Object raw) {
        return transactions.execute(status -> {
            lockUser(userId);Map<String,Object> row=row(userId,id);
            if (row.get("spread_id")==null) throw new AuthException(409,"旧牌阵已停用，请重新抽牌");
            int required=TarotReadingCatalogue.positions(String.valueOf(row.get("spread_id"))).size();
            List<Integer> positions=validatePicks(raw,required);
            if (row.get("consumed_task_id")!=null || !((Timestamp)row.get("expires_at")).toInstant().isAfter(Instant.now()))
                throw new AuthException(409,"牌阵已消费或过期，请重新抽牌");
            if (row.get("pick_positions")!=null) {
                if (!positions.equals(read(row.get("pick_positions"),new TypeReference<List<Integer>>() {})))
                    throw new AuthException(409,"牌阵已经选定，不能更改");
                return decode(row);
            }
            List<Map<String,Object>> hidden=read(row.get("deck_order_json"),new TypeReference<>() {});
            List<Map<String,Object>> slots=TarotReadingCatalogue.positions(String.valueOf(row.get("spread_id")));
            List<Map<String,Object>> cards=new ArrayList<>();
            for (int i=0;i<positions.size();i++) {
                Map<String,Object> card=new LinkedHashMap<>(hidden.get(positions.get(i)));
                card.put("order",i);card.put("fanIndex",positions.get(i));
                card.put("position",slots.get(i).get("id"));card.put("positionLabel",slots.get(i).get("label"));cards.add(card);
            }
            jdbc.update("UPDATE matchmaking_tarot_draws SET pick_positions=?,cards_json=?,picked_at=CURRENT_TIMESTAMP WHERE id=?",json(positions),json(cards),id);
            return decode(row(userId,id));
        });
    }
    static List<Integer> validatePicks(Object raw,int required) {
        if (!(raw instanceof List<?> list) || list.size()!=required) throw new AuthException(400,"选牌数量无效");
        List<Integer> result=new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Integer i) || i<0 || i>21 || result.contains(i)) throw new AuthException(400,"选牌位置须为互不重复的0–21整数");
            result.add(i);
        }
        return result;
    }
    public Map<String,Object> current(long userId) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT * FROM matchmaking_tarot_draws WHERE user_id=? AND spread_id IS NOT NULL AND consumed_report_id IS NULL
            AND (expires_at>CURRENT_TIMESTAMP OR consumed_task_id IS NOT NULL) ORDER BY created_at DESC,id DESC LIMIT 1
            """,userId);
        Map<String,Object> result=new LinkedHashMap<>();result.put("draw",rows.isEmpty()?null:decode(rows.get(0)));return result;
    }
    Map<String,Object> consume(long userId,String id,String taskId) {
        lockUser(userId);Map<String,Object> row=row(userId,id);
        if (row.get("spread_id")==null || row.get("picked_at")==null || row.get("consumed_task_id")!=null
                || !((Timestamp)row.get("expires_at")).toInstant().isAfter(Instant.now())) throw new AuthException(409,"请先完成有效牌阵的选牌");
        jdbc.update("UPDATE matchmaking_tarot_draws SET consumed_task_id=? WHERE id=?",taskId,id);
        return decode(row(userId,id));
    }
    void markReport(String drawId,String taskId,String reportId) {
        if (drawId==null || drawId.isBlank()) return;
        if (jdbc.update("UPDATE matchmaking_tarot_draws SET consumed_report_id=? WHERE id=? AND consumed_task_id=? AND consumed_report_id IS NULL",reportId,drawId,taskId)!=1)
            throw new IllegalStateException("牌阵报告关联失败");
    }
    void restoreAfterFailure(String drawId,String taskId) {
        if (drawId==null || drawId.isBlank()) return;
        jdbc.update("UPDATE matchmaking_tarot_draws SET consumed_task_id=NULL,expires_at=? WHERE id=? AND consumed_task_id=? AND consumed_report_id IS NULL",Timestamp.from(Instant.now().plusSeconds(86400)),drawId,taskId);
    }
    void deleteForReport(String id) { jdbc.update("DELETE FROM matchmaking_tarot_draws WHERE consumed_report_id=?",id); }
    void deleteForUser(long id) { jdbc.update("DELETE FROM matchmaking_tarot_draws WHERE user_id=?",id); }
    void cleanExpired() {
        // Replaced draws remain until the rate window has elapsed; cleanup cannot bypass hourly limits.
        jdbc.update("DELETE FROM matchmaking_tarot_draws WHERE expires_at<=CURRENT_TIMESTAMP AND created_at<? AND consumed_task_id IS NULL AND consumed_report_id IS NULL",Timestamp.from(Instant.now().minusSeconds(3600)));
        jdbc.update("DELETE FROM matchmaking_tarot_draws WHERE consumed_report_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM matchmaking_reports r WHERE r.id=matchmaking_tarot_draws.consumed_report_id)");
    }
    private void lockUser(long id) { if(jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,id).size()!=1) throw new AuthException(404,"用户不存在"); }
    private Map<String,Object> row(long userId,String id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM matchmaking_tarot_draws WHERE id=? AND user_id=? FOR UPDATE",id,userId);
        if (rows.isEmpty()) throw new AuthException(404,"牌阵不存在");return rows.get(0);
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("牌阵保存失败",e); } }
    private <T> T read(Object value,TypeReference<T> type) { try { return mapper.readValue(String.valueOf(value),type); } catch(Exception e) { throw new IllegalStateException("牌阵读取失败",e); } }
    private Map<String,Object> decode(Map<String,Object> row) {
        Map<String,Object> result=read(row.get("reading_context"),new TypeReference<>() {});
        result.put("drawId",row.get("id"));result.put("catalogueVersion",TarotReadingCatalogue.VERSION);result.put("deckVersion",row.get("deck_version"));
        result.put("state",row.get("consumed_task_id")!=null?"consumed":row.get("picked_at")!=null?"picked":"shuffled");
        result.put("fanSize",22);result.put("picksRequired",TarotReadingCatalogue.positions(String.valueOf(row.get("spread_id"))).size());
        result.put("picks",row.get("pick_positions")==null?null:read(row.get("pick_positions"),new TypeReference<List<Integer>>() {}));
        result.put("cards",read(row.get("cards_json"),new TypeReference<List<Map<String,Object>>>() {}));
        result.put("drawnAt",((Timestamp)row.get("created_at")).toInstant().toString());result.put("expiresAt",((Timestamp)row.get("expires_at")).toInstant().toString());return result;
    }
}
