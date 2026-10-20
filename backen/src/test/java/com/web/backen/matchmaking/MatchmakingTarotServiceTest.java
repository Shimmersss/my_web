package com.web.backen.matchmaking;

import com.web.backen.auth.AuthException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MatchmakingTarotServiceTest {
    private EmbeddedDatabase database;private JdbcTemplate jdbc;private MatchmakingTarotService service;private TransactionTemplate tx;
    @BeforeEach void setup() {
        database=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();jdbc=new JdbcTemplate(database);
        jdbc.update("INSERT INTO users(id,username,password_hash,role,credits) VALUES(1,'alice','x','USER',0),(2,'bob','x','USER',0)");
        service=TarotTestSupport.tarot(jdbc);tx=new TransactionTemplate(new DataSourceTransactionManager(database));
    }
    @AfterEach void teardown() { database.shutdown(); }
    @Test void concealedDeckAndStableThemesForAllSpreads() {
        for(String spread:List.of("single","triangle","choice")) {
            var draw=service.draw(1,TarotTestSupport.form(spread));String id=(String)draw.get("drawId");
            assertEquals("shuffled",draw.get("state"));assertEquals(List.of(),draw.get("cards"));assertNull(draw.get("picks"));
            assertFalse(draw.containsKey("deck_order_json"));assertFalse(draw.containsKey("deckOrder"));
            int count=(int)draw.get("picksRequired");var positions=java.util.stream.IntStream.range(0,count).boxed().toList();
            var picked=service.picks(1,id,positions);assertEquals("picked",picked.get("state"));assertEquals(picked,service.picks(1,id,positions));
            assertEquals(picked,service.current(1).get("draw"));List<?> cards=(List<?>)picked.get("cards");assertEquals(count,cards.size());
            Set<Object> unique=new HashSet<>();for(Object raw:cards) { Map<?,?> card=(Map<?,?>)raw;unique.add(card.get("cardId"));assertTrue(Set.of("upright","reversed").contains(card.get("orientation")));assertTrue(String.valueOf(card.get("themeId")).matches("theme-0[1-5]"));assertNotNull(card.get("themeName")); }
            assertEquals(count,unique.size());assertEquals(count,TarotReadingCatalogue.snapshot(picked).size());
            assertEquals(409,assertThrows(AuthException.class,()->service.picks(1,id,java.util.stream.IntStream.range(10,10+count).boxed().toList())).getStatus());
        }
    }
    @Test void consumptionFailureRestoresSameDrawAndBlocksNewDeck() {
        String id=(String)service.draw(1,TarotTestSupport.form("single")).get("drawId");
        assertEquals(409,assertThrows(AuthException.class,()->tx.execute(s->service.consume(1,id,"task"))).getStatus());
        var picked=service.picks(1,id,List.of(0));tx.execute(s->service.consume(1,id,"task"));
        assertEquals("consumed",((Map<?,?>)service.current(1).get("draw")).get("state"));
        assertEquals(409,assertThrows(AuthException.class,()->service.draw(1,TarotTestSupport.form("single"))).getStatus());
        assertThrows(AuthException.class,()->service.picks(1,id,List.of(0)));
        tx.executeWithoutResult(s->service.restoreAfterFailure(id,"task"));assertEquals(picked.get("cards"),((Map<?,?>)service.current(1).get("draw")).get("cards"));
        assertEquals("picked",((Map<?,?>)service.current(1).get("draw")).get("state"));
    }
    @Test void replacementAndCleanupCannotBypassHourlyLimit() {
        String first=null;for(int i=0;i<12;i++){var draw=service.draw(1,TarotTestSupport.form("single"));if(i==0)first=(String)draw.get("drawId");service.cleanExpired();}
        assertEquals(12,jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_tarot_draws",Integer.class));
        assertEquals(429,assertThrows(AuthException.class,()->service.draw(1,TarotTestSupport.form("single"))).getStatus());
        String id=first;assertEquals(409,assertThrows(AuthException.class,()->service.picks(1,id,List.of(0))).getStatus());
    }
    @Test void ownershipAndStrictPositionValidation() {
        String id=(String)service.draw(1,TarotTestSupport.form("triangle")).get("drawId");
        assertEquals(404,assertThrows(AuthException.class,()->service.picks(2,id,List.of(0,1,2))).getStatus());
        for(Object positions:List.of(List.of(0,0,2),List.of(0,1,22),List.of(0,1),List.of(0,1,2.0),List.of("0",1,2)))
            assertEquals(400,assertThrows(AuthException.class,()->service.picks(1,id,positions)).getStatus());
    }
    @Test void parallelIdenticalPicksAreIdempotent() throws Exception {
        String id=(String)service.draw(1,TarotTestSupport.form("triangle")).get("drawId");var pool=Executors.newFixedThreadPool(4);var start=new CountDownLatch(1);
        try { List<Future<Map<String,Object>>> futures=new ArrayList<>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{start.await();return service.picks(1,id,List.of(3,17,8));}));start.countDown();
            var expected=futures.get(0).get(5,TimeUnit.SECONDS);for(var future:futures)assertEquals(expected,future.get(5,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
}
