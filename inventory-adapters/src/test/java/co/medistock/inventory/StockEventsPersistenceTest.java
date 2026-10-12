package co.medistock.inventory;

import co.medistock.inventory.adapters.*;
import co.medistock.inventory.core.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="INV_DB_URL",matches=".+")
class StockEventsPersistenceTest {
    final EntriesPersistenceTest fixture=new EntriesPersistenceTest();
    JdbcStockEvents consumer;
    UUID actor=UUID.randomUUID(),lot;
    List<StockEvent> events;
    @BeforeEach void setup() {
        fixture.setup(); consumer=new JdbcStockEvents(fixture.jdbc);
        var entry=fixture.prepare("prepare-owner",fixture.request).entry(); lot=entry.lotId();
        fixture.transition("activate-owner",entry.id(),"activate");
        fixture.tx.execute(s -> new JdbcExits(fixture.jdbc,Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"),ZoneOffset.UTC))
            .create(fixture.actor,"exit-owner",new NewExit(lot,2,"Entrega"),"owner-trace"));
        var mapper=JsonMapper.builder().build();
        events=fixture.jdbc.query("SELECT payload::text FROM inventory.outbox WHERE lot_id=? ORDER BY sequence",
            (r,n) -> new StockEvent(mapper.readValue(r.getString(1),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){})),lot);
    }
    StockEvents.Result apply(StockEvent event) { return fixture.tx.execute(s -> consumer.apply(actor,event)); }
    int receipts() { return fixture.jdbc.queryForObject("SELECT count(*) FROM inventory.stock_event_inbox WHERE lot_id=?",Integer.class,lot); }
    long checkpoint() { return fixture.jdbc.queryForObject("SELECT last_sequence FROM inventory.stock_event_checkpoint WHERE lot_id=?",Long.class,lot); }
    @Test void replayAfterLostResponseDoesNotApplyAgain() {
        assertTrue(apply(events.get(0)).applied()); assertFalse(apply(events.get(0)).applied());
        assertEquals(1,receipts()); assertEquals(events.get(0).sequence(),checkpoint());
        assertEquals(actor,fixture.jdbc.queryForObject("SELECT processed_by FROM inventory.stock_event_inbox WHERE event_id=?",UUID.class,events.get(0).eventId()));
        assertEquals(8,fixture.balance(lot));
    }
    @Test void outOfOrderEventIsRecordedWithoutRegressingCheckpoint() {
        assertTrue(apply(events.get(1)).applied()); assertFalse(apply(events.get(0)).applied());
        assertEquals(2,receipts()); assertEquals(events.get(1).sequence(),checkpoint()); assertEquals(8,fixture.balance(lot));
    }
    @Test void differentPayloadOrUnknownEventDoesNotCreateReceipt() {
        var body=new HashMap<>(events.get(0).payload()); body.put("quantity",11); body.put("delta",11);
        assertEquals(409,assertThrows(InventoryFailure.class,() -> apply(new StockEvent(body))).status());
        String unknown=UUID.randomUUID().toString(); body.put("eventId",unknown); body.put("movementId",unknown);
        assertEquals(404,assertThrows(InventoryFailure.class,() -> apply(new StockEvent(body))).status());
        assertEquals(0,receipts());
        assertEquals(0,fixture.jdbc.queryForObject("SELECT count(*) FROM inventory.stock_event_checkpoint WHERE lot_id=?",Integer.class,lot));
    }
    @Test void failureAfterAllWritesRollsBackInboxAndCheckpoint() {
        assertThrows(IllegalStateException.class,() -> fixture.tx.execute(s -> {
            consumer.apply(actor,events.get(0)); throw new IllegalStateException("Synthetic failure after writes");
        }));
        assertEquals(0,receipts());
        assertEquals(0,fixture.jdbc.queryForObject("SELECT count(*) FROM inventory.stock_event_checkpoint WHERE lot_id=?",Integer.class,lot));
        assertTrue(apply(events.get(0)).applied()); assertEquals(8,fixture.balance(lot));
    }
    @Test void concurrentDeliveryCreatesOneEffect() throws Exception {
        var pool=Executors.newFixedThreadPool(2); var ready=new CountDownLatch(2);
        try {
            var tasks=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++) tasks.add(pool.submit(() -> {
                ready.countDown(); if(!ready.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Race setup timeout");
                return apply(events.get(0)).applied();
            }));
            var applied=new ArrayList<Boolean>(); for(var task:tasks) applied.add(task.get(30,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(applied,true)); assertEquals(1,Collections.frequency(applied,false));
            assertEquals(1,receipts()); assertEquals(events.get(0).sequence(),checkpoint());
        } finally { pool.shutdownNow(); }
    }
    @Test void fieldOrderDoesNotChangeReplayHash() {
        assertTrue(apply(events.get(0)).applied()); var reversed=new LinkedHashMap<String,Object>();
        var keys=new ArrayList<>(events.get(0).payload().keySet()); Collections.reverse(keys);
        for(String key:keys) reversed.put(key,events.get(0).payload().get(key));
        assertFalse(apply(new StockEvent(reversed)).applied()); assertEquals(1,receipts());
    }
}
