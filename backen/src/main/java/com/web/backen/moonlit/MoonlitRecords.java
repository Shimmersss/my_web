package com.web.backen.moonlit;

import com.web.backen.auth.AuthException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.*;

/** Called inside the caller's transaction; user lock serializes all draw/check-in entries. */
public final class MoonlitRecords {
    private MoonlitRecords() {}
    public static void lockUser(JdbcTemplate jdbc,long userId) {
        if(jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",userId).isEmpty())
            throw new AuthException(404,"用户不存在");
    }
    public static Map<String,Object> record(JdbcTemplate jdbc,long userId,String variantId,String method,LocalDate date,String drawId) {
        Map<String,Object> card=MoonlitDeck.byId(variantId);
        if(card==null)throw new AuthException(400,"主题卡无效");
        lockUser(jdbc,userId);
        var rows=jdbc.queryForList("SELECT acquisitions FROM moonlit_collections WHERE user_id=? AND card_id=? FOR UPDATE",userId,variantId);
        int previous=rows.isEmpty()?0:((Number)rows.get(0).get("acquisitions")).intValue();
        int count=Math.addExact(previous,1);String type=previous==0?"first":"repeat";
        if(rows.isEmpty())jdbc.update("INSERT INTO moonlit_collections(user_id,card_id,acquisitions) VALUES(?,?,?)",userId,variantId,count);
        else jdbc.update("UPDATE moonlit_collections SET acquisitions=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND card_id=?",count,userId,variantId);
        jdbc.update("INSERT INTO moonlit_draws(id,user_id,card_id,method,result_type,acquisition_count,draw_date) VALUES(?,?,?,?,?,?,?)",drawId,userId,variantId,method,type,count,date);
        var out=new LinkedHashMap<String,Object>(card);out.put("drawId",drawId);out.put("method",method);out.put("resultType",type);out.put("previous",previous);out.put("count",count);return out;
    }
}
