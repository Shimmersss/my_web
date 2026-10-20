package com.web.backen.moonlit;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.QuotaService;
import com.web.backen.matchmaking.TarotDeck;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.security.SecureRandom;
import static org.junit.jupiter.api.Assertions.*;

class MoonlitMySqlIT {
 @Test void independentDrawsMigrationDailyConcurrencyAndRollback() throws Exception {
  String url=Objects.requireNonNull(System.getenv("WEB_TEST_MYSQL_URL"));assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:"));
  String user=System.getenv("WEB_TEST_MYSQL_USER"),password=System.getenv("WEB_TEST_MYSQL_PASSWORD");
  var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));String db="moonlit_test_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE "+db);
  String host=url.substring(0,url.indexOf('/',"jdbc:mysql://".length()));var ds=new DriverManagerDataSource(host+"/"+db+"?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8",user,password);
  new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));var quota=new QuotaService(jdbc);quota.initializeDefaults();
  jdbc.update("INSERT INTO users(id,username,password_hash,role,credits) VALUES(1,'one','x','USER',100),(2,'two','x','USER',0),(3,'three','x','USER',0),(4,'legacy-daily','x','USER',0)");
  jdbc.update("INSERT INTO moonlit_collections(user_id,card_id,acquisitions) VALUES(1,'major-18',8),(2,'major-00',2)");
  var migration=new MoonlitVariantMigration(jdbc,tx);migration.migrate();migration.migrate();
  assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM moonlit_collections WHERE user_id=1 AND card_id LIKE '%-theme-%'",Integer.class));
  assertEquals(8,jdbc.queryForObject("SELECT SUM(acquisitions) FROM moonlit_collections WHERE user_id=1 AND card_id LIKE '%-theme-%'",Integer.class));
  assertEquals(8,jdbc.queryForObject("SELECT acquisitions FROM moonlit_collections WHERE user_id=1 AND card_id='major-18'",Integer.class));
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM moonlit_collections WHERE user_id=2 AND card_id LIKE '%-theme-%'",Integer.class));
  SecureRandom fixed=new SecureRandom(){@Override public int nextInt(int bound){assertEquals(110,bound);return 4;}};
  var service=new MoonlitService(jdbc,tx,quota,fixed);
  var first=service.draw(1,"credits",1);assertEquals("major-00-theme-05",first.get("variantId"));assertEquals("first",first.get("resultType"));assertEquals(97,quota.balance(1));
  var repeat=service.draw(1,"credits",1);assertEquals("repeat",repeat.get("resultType"));assertEquals(2,repeat.get("count"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM moonlit_collections WHERE user_id=1 AND card_id='major-00-theme-01'",Integer.class));
  assertThrows(AuthException.class,()->service.draw(2,"credits",1));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM moonlit_draws WHERE user_id=2",Integer.class));
  assertThrows(AuthException.class,()->service.setDisplay(1,"major-00",4));
  assertThrows(RuntimeException.class,()->tx.executeWithoutResult(status->{quota.claimDailyCheckin(3);throw new RuntimeException("rollback");}));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM daily_checkins WHERE user_id=3",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM moonlit_collections WHERE user_id=3",Integer.class));assertEquals(0,quota.balance(3));
  var pool=Executors.newFixedThreadPool(4);var start=new CountDownLatch(1);var futures=new ArrayList<Future<Map<String,Object>>>();
  for(int i=0;i<4;i++){final int n=i;futures.add(pool.submit(()->{start.await();return n%2==0?service.draw(3,"daily",1):tx.execute(status->quota.claimDailyCheckin(3));}));}
  start.countDown();Set<Object> variants=new HashSet<>();for(var f:futures){var r=f.get(15,TimeUnit.SECONDS);var card=r.containsKey("tarotCard")?(Map<?,?>)r.get("tarotCard"):r;variants.add(card.get("variantId"));}pool.shutdownNow();assertEquals(1,variants.size());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM moonlit_draws WHERE user_id=3 AND method='daily'",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT SUM(acquisitions) FROM moonlit_collections WHERE user_id=3",Integer.class));assertEquals(2,quota.balance(3));
  var old=TarotDeck.dailyCard("major-18");old.put("deckVersion",TarotDeck.VERSION);String payload=new ObjectMapper().writeValueAsString(old);jdbc.update("INSERT INTO daily_checkins(user_id,checkin_date,deck_version,card_id,tarot_payload) VALUES(4,CURRENT_DATE,?,?,?)",TarotDeck.VERSION,"major-18",payload);
  assertEquals(old.get("dailyGuidanceZh"),((Map<?,?>)quota.dailyCheckinStatus(4).get("tarotCard")).get("dailyGuidanceZh"));assertEquals(0,quota.balance(4));
  var spendingPool=Executors.newFixedThreadPool(4);var drawFutures=new ArrayList<Future<?>>();for(int i=0;i<4;i++)drawFutures.add(spendingPool.submit(()->service.draw(1,"credits",1)));for(var f:drawFutures)f.get(15,TimeUnit.SECONDS);spendingPool.shutdownNow();
  assertEquals(6,jdbc.queryForObject("SELECT acquisitions FROM moonlit_collections WHERE user_id=1 AND card_id='major-00-theme-05'",Integer.class));assertEquals(82,quota.balance(1));
  assertEquals(110,MoonlitDeck.catalogue().size());assertEquals(110,MoonlitDeck.catalogue().stream().map(c->c.get("dailyGuidanceZh")).distinct().count());
  // Keep the disposable database as verification evidence; removal requires user authorization.
  System.out.println("Moonlit MySQL checks passed; retained isolated database "+db);
 }
}
