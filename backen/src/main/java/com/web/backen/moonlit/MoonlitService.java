package com.web.backen.moonlit;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.QuotaService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.security.SecureRandom;
import java.util.*;

@Service
public class MoonlitService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final QuotaService quota;
    private final SecureRandom random;
    @org.springframework.beans.factory.annotation.Autowired
    public MoonlitService(JdbcTemplate jdbc,TransactionTemplate tx,QuotaService quota) { this(jdbc,tx,quota,new SecureRandom()); }
    MoonlitService(JdbcTemplate jdbc,TransactionTemplate tx,QuotaService quota,SecureRandom random) {this.jdbc=jdbc;this.tx=tx;this.quota=quota;this.random=random;}
    public Map<String,Object> state(long userId) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("credits",quota.balance(userId));out.put("price",quota.moonlitCreditPerDraw());out.put("daily",quota.dailyCheckinStatus(userId));out.put("deckVersion",MoonlitDeck.VERSION);
        out.put("collection",jdbc.queryForList("SELECT card_id variantId, acquisitions count FROM moonlit_collections WHERE user_id=? AND card_id LIKE 'major-%-theme-%'",userId));
        out.put("history",jdbc.queryForList("SELECT card_id variantId,method,result_type resultType,acquisition_count count,created_at at FROM moonlit_draws WHERE user_id=? ORDER BY created_at DESC LIMIT 50",userId));
        return out;
    }
    public Map<String,Object> draw(long userId,String method,int count) {
        if(!"daily".equals(method)&&!"credits".equals(method))throw new AuthException(400,"抽取方式无效");
        if("daily".equals(method)&&count!=1)throw new AuthException(400,"每日抽卡仅支持单抽");
        if("credits".equals(method)&&count!=1&&count!=5&&count!=10)throw new AuthException(400,"积分抽卡仅支持单抽、五连或十连");
        return tx.execute(status->{
            MoonlitRecords.lockUser(jdbc,userId);
            LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
            if("daily".equals(method)) {
                Map<String,Object> daily=quota.claimDailyCheckin(userId);
                @SuppressWarnings("unchecked") var saved=(Map<String,Object>)daily.get("tarotCard");
                if(saved==null)throw new AuthException(500,"今日牌读取失败");
                var out=new LinkedHashMap<>(saved);out.put("method","daily");out.put("credits",quota.balance(userId));out.put("daily",daily);return out;
            }
            String id=UUID.randomUUID().toString();int price=quota.moonlitCreditPerDraw();
            quota.spend(userId,Math.multiplyExact(price,count),"moonlit-draw",id,"月下独立主题卡 x"+count);
            List<Map<String,Object>> results=new ArrayList<>();
            for(int i=0;i<count;i++) {
                String variant=(String)MoonlitDeck.randomCard(random).get("variantId");
                var result=MoonlitRecords.record(jdbc,userId,variant,method,today,count==1?id:UUID.randomUUID().toString());
                result.put("credits",quota.balance(userId));results.add(result);
            }
            if(count==1)return results.get(0);
            return Map.of("batchId",id,"count",count,"results",results,"credits",quota.balance(userId));
        });
    }
    public void setDisplay(long userId,String variantId,int theme) {
        // Old callers cannot turn an acquisition count into ownership of another theme.
        if(MoonlitDeck.byId(variantId)==null||theme!=0)throw new AuthException(400,"每个主题是独立卡片，不提供升级切换");
        Integer owned=jdbc.queryForObject("SELECT COUNT(*) FROM moonlit_collections WHERE user_id=? AND card_id=? AND acquisitions>0",Integer.class,userId,variantId);
        if(owned==null||owned==0)throw new AuthException(404,"尚未收藏这张主题卡");
    }
}
