package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TarotReadingTaskTest {
    @TempDir Path directory;
    private EmbeddedDatabase db;private JdbcTemplate jdbc;private QuotaService quota;private RuntimeConfigService runtime;private MatchmakingTarotService tarot;private MatchmakingService service;
    private final AuthUser user=new AuthUser(1,"alice","USER",20,true);
    @BeforeEach void setup() {
        db=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();jdbc=new JdbcTemplate(db);
        jdbc.update("INSERT INTO users(id,username,password_hash,role,credits) VALUES(1,'alice','x','USER',20)");quota=new QuotaService(jdbc);quota.initializeDefaults();runtime=TarotTestSupport.enabled();tarot=TarotTestSupport.tarot(jdbc);service=service(TarotTestSupport::reading,quota);
    }
    @AfterEach void cleanup(){service.shutdown();db.shutdown();}
    private MatchmakingService service(MatchmakingReportAgent agent,QuotaService q) { return new MatchmakingService(jdbc,new ObjectMapper(),agent,q,null,runtime,null,tarot,directory.toString()); }
    private String admit() {return (String)service.createTask(user,TarotTestSupport.body(jdbc,1)).get("taskId");}
    private Map<String,Object> await(String id) throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){var task=service.task(user,id);if(Set.of("done","error").contains(task.get("status")))return task;Thread.sleep(10);}throw new AssertionError("not terminal");}
    @Test void retiredVersionsAndDisabledAiNeverChargeOrConsume() {
        var body=TarotTestSupport.body(jdbc,1);
        for(String version:List.of("","market-positioning-v3","relationship-exploration-v1"))assertEquals(410,assertThrows(AuthException.class,()->service.createTask(user,Map.of("reportVersion",version))).getStatus());
        assertEquals(400,assertThrows(AuthException.class,()->service.createTask(user,Map.of("reportVersion","future-version"))).getStatus());
        when(runtime.tarotAiEnabled()).thenReturn(false);assertEquals(403,assertThrows(AuthException.class,()->service.createTask(user,body)).getStatus());
        assertEquals(20,quota.balance(1));assertEquals("picked",((Map<?,?>)tarot.current(1).get("draw")).get("state"));
        assertFalse(service.status(user).containsKey("relationshipEnabled"));assertEquals(false,service.status(user).get("tarotAiEnabled"));
        assertFalse(service.status(new AuthUser(1,"trial","MATCHMAKING_TRIAL",0,true)).containsKey("credits"));
    }
    @Test void successfulReportContainsImmutableCardAndThemeSnapshot() throws Exception {
        service.init();String id=admit();var task=await(id);assertEquals("done",task.get("status"));var report=service.report(user,String.valueOf(task.get("reportId")));
        assertEquals("tarot-reading-v1",report.get("reportVersion"));assertEquals(3,((List<?>)report.get("cards")).size());
        for(Object raw:(List<?>)report.get("cards")){var card=(Map<?,?>)raw;for(String key:List.of("meaning","keywords","advice","themeName","themeMeaning","themeId","variantId"))assertNotNull(card.get(key));}
        assertEquals("圣三角 · 事业学业",service.reportSummaries(user).get(0).get("subtitle"));assertNull(tarot.current(1).get("draw"));assertEquals(18,quota.balance(1));
    }
    @Test void failedRefundPreservesEvidenceAndRetriesWithoutChangingSelectedCards() throws Exception {
        var failing=spy(quota);doThrow(new IllegalStateException("refund offline")).when(failing).refund(anyLong(),anyString());service=service(input->{throw new IllegalStateException("provider failed");},failing);
        var body=TarotTestSupport.body(jdbc,1);var before=(Map<?,?>)tarot.current(1).get("draw");service.init();String id=(String)service.createTask(user,body).get("taskId");
        assertEquals("error",await(id).get("status"));assertTrue((boolean)service.task(user,id).get("compensationPending"));assertEquals(18,quota.balance(1));
        assertEquals("consumed",((Map<?,?>)tarot.current(1).get("draw")).get("state"));assertEquals(429,assertThrows(AuthException.class,()->service.createTask(user,body)).getStatus());
        assertTrue(jdbc.queryForObject("SELECT payload FROM matchmaking_tasks WHERE id=?",String.class,id).contains("\"compensationPending\":true"));
        doCallRealMethod().when(failing).refund(anyLong(),anyString());service.retryCompensations();assertFalse((boolean)service.task(user,id).get("compensationPending"));assertEquals(20,quota.balance(1));
        var after=(Map<?,?>)tarot.current(1).get("draw");assertEquals("picked",after.get("state"));assertEquals(before.get("cards"),after.get("cards"));
        service.retryCompensations();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM credit_transactions WHERE kind='REFUND'",Integer.class));
    }
    @Test void queuedTaskRestartRefundsAndDoesNotRerunProvider() {
        String id=admit();service.shutdown();service=service(input->{throw new AssertionError("must not rerun interrupted task");},quota);service.init();
        assertEquals("error",service.task(user,id).get("status"));assertEquals(20,quota.balance(1));assertEquals("picked",((Map<?,?>)tarot.current(1).get("draw")).get("state"));
    }
}
