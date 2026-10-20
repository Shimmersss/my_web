package com.web.backen.moonlit;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Preserve historical progression rows, convert previously owned themes once. */
@Component
@DependsOn("moonlitSchemaMigration")
public class MoonlitVariantMigration {
    private final JdbcTemplate jdbc; private final TransactionTemplate tx;
    public MoonlitVariantMigration(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}
    @PostConstruct
    public void migrate(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS moonlit_variant_migrations (user_id BIGINT NOT NULL, card_id VARCHAR(32) NOT NULL, migrated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(user_id,card_id), FOREIGN KEY(user_id) REFERENCES users(id))");
        var old=jdbc.queryForList("SELECT user_id,card_id,acquisitions,first_acquired_at FROM moonlit_collections WHERE card_id LIKE 'major-%' AND card_id NOT LIKE '%-theme-%' AND acquisitions>0");
        for(var row:old) tx.executeWithoutResult(status->{
            long user=((Number)row.get("user_id")).longValue();String base=String.valueOf(row.get("card_id"));int n=((Number)row.get("acquisitions")).intValue();
            MoonlitRecords.lockUser(jdbc,user);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM moonlit_variant_migrations WHERE user_id=? AND card_id=?",Integer.class,user,base)>0)return;
            int themes=Math.min(5,n);
            for(int i=1;i<=themes;i++){
                String variant=base+String.format("-theme-%02d",i);
                if(MoonlitDeck.byId(variant)==null)throw new IllegalStateException("历史收藏牌ID无效");
                int amount=i==1?Math.max(1,n-4):1;
                var existing=jdbc.queryForList("SELECT acquisitions FROM moonlit_collections WHERE user_id=? AND card_id=? FOR UPDATE",user,variant);
                if(existing.isEmpty())jdbc.update("INSERT INTO moonlit_collections(user_id,card_id,acquisitions,first_acquired_at) VALUES(?,?,?,?)",user,variant,amount,row.get("first_acquired_at"));
                else jdbc.update("UPDATE moonlit_collections SET acquisitions=acquisitions+? WHERE user_id=? AND card_id=?",amount,user,variant);
            }
            jdbc.update("INSERT INTO moonlit_variant_migrations(user_id,card_id) VALUES(?,?)",user,base);
        });
    }
}
