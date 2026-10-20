package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.RuntimeConfigService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.mockito.Mockito.*;

final class TarotTestSupport {
    static RuntimeConfigService enabled() { var runtime=mock(RuntimeConfigService.class);when(runtime.tarotAiEnabled()).thenReturn(true);when(runtime.matchmakingMaxHistory()).thenReturn(20);when(runtime.matchmakingMaxGlobalHistory()).thenReturn(200);return runtime; }
    static MatchmakingTarotService tarot(JdbcTemplate jdbc) { return new MatchmakingTarotService(jdbc,new ObjectMapper(),new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))); }
    static Map<String,Object> form(String spread) { return Map.of("spreadId",spread,"topicId","career","question","接下来如何推进这个项目？","optionA","留下","optionB","换方向"); }
    static Map<String,Object> body(JdbcTemplate jdbc,long userId) {
        var tarot=tarot(jdbc);String id=(String)tarot.draw(userId,form("triangle")).get("drawId");tarot.picks(userId,id,List.of(3,17,8));return Map.of("reportVersion","tarot-reading-v1","drawId",id);
    }
    static Map<String,Object> reading(Map<String,Object> input) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("title","月下前行指引");result.put("overview","观".repeat(60));result.put("synthesis","思".repeat(80));result.put("caution","慎".repeat(20));
        result.put("cards",((List<?>)input.get("cards")).stream().map(raw->{ Map<?,?> card=(Map<?,?>)raw;return Map.of("position",card.get("position"),"cardId",card.get("cardId"),"orientation",card.get("orientation"),"interpretation","读".repeat(80),"advice","行".repeat(20)); }).toList());
        result.put("guidance",Map.of("actions",List.of("做".repeat(10),"看".repeat(10),"想".repeat(10)),"reflection","问".repeat(20)));
        if("choice".equals(((Map<?,?>)input.get("spread")).get("id")))result.put("comparison",Map.of("optionA","甲".repeat(60),"optionB","乙".repeat(60),"contrast","比".repeat(60)));
        return result;
    }
}
