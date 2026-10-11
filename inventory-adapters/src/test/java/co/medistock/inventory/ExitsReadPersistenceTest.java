package co.medistock.inventory;

import co.medistock.inventory.adapters.*;
import co.medistock.inventory.core.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="INV_DB_URL",matches=".+")
class ExitsReadPersistenceTest {
    final EntriesPersistenceTest fixture=new EntriesPersistenceTest();
    final Clock clock=Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"),ZoneOffset.UTC);
    JdbcExits exits;
    JdbcInventoryRead read;
    TransactionTemplate readTx;
    Entry entry;
    @BeforeEach void setup() {
        fixture.setup(); exits=new JdbcExits(fixture.jdbc,clock); read=new JdbcInventoryRead(fixture.jdbc,clock);
        readTx=new TransactionTemplate(fixture.tx.getTransactionManager());
        readTx.setReadOnly(true); readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        entry=fixture.prepare("prepare-stock",fixture.request).entry();
        fixture.transition("activate-stock",entry.id(),"activate");
    }
    Exits.Result create(String key,int quantity) {
        return fixture.tx.execute(s -> exits.create(fixture.actor,key,new NewExit(entry.lotId(),quantity,"Entrega"),"exit-trace"));
    }
    InventoryQuery query(Map<String,List<String>> values,boolean movements) {
        var filters=new HashMap<>(values); filters.put("lotId",List.of(entry.lotId().toString()));
        return InventoryQuery.parse(filters,movements);
    }
    InventoryRead.Page<InventoryRead.Stock> stock(Map<String,List<String>> filters) {
        return readTx.execute(s -> read.stock(query(filters,false)));
    }
    @Test void exitCommitsStockMovementEventAndAudit() {
        var result=create("exit-success",3); assertTrue(result.created());
        assertEquals(7,result.exit().remainingQuantity()); assertEquals(fixture.actor,result.exit().createdBy());
        assertEquals(clock.instant(),result.exit().createdAt()); assertEquals(result.exit(),exits.find(result.exit().id()));
        assertEquals(7,fixture.balance(entry.lotId())); assertEquals(2,fixture.events(entry.lotId()));
        String sql="SELECT (payload->>'delta')||','||(payload->>'onHandQuantity')||','||(payload->>'traceId') FROM inventory.outbox WHERE lot_id=? AND payload->>'movementType'='EXIT'";
        assertEquals("-3,7,exit-trace",fixture.jdbc.queryForObject(sql,String.class,entry.lotId()));
    }
    @Test void replayReturnsOriginalSnapshotWithoutAnotherDiscount() {
        var first=create("exit-replay",3); create("exit-second",4);
        var replay=create("exit-replay",3);
        assertFalse(replay.created()); assertEquals(first.exit(),replay.exit());
        assertEquals(7,replay.exit().remainingQuantity()); assertEquals(3,fixture.balance(entry.lotId()));
        assertEquals(3,fixture.events(entry.lotId()));
        assertEquals(409,assertThrows(InventoryFailure.class,() -> create("exit-replay",2)).status());
    }
    @Test void insufficientAndMissingStockDoNotPersistExitIntents() {
        var failure=assertThrows(InventoryFailure.class,() -> create("exit-too-large",11));
        assertEquals(422,failure.status()); assertEquals("INSUFFICIENT_STOCK",failure.code());
        assertEquals(404,assertThrows(InventoryFailure.class,() -> fixture.tx.execute(s -> exits.create(
            fixture.actor,"exit-missing",new NewExit(UUID.randomUUID(),1,"Entrega"),"exit-trace"))).status());
        assertEquals(10,fixture.balance(entry.lotId())); assertEquals(1,fixture.events(entry.lotId()));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT count(*) FROM inventory.exit_request WHERE actor=?",Integer.class,fixture.actor));
    }
    @Test void expiryUsesBogotaMidnightAndStockReadKeepsOnHand() {
        exits=new JdbcExits(fixture.jdbc,Clock.fixed(Instant.parse("2099-01-01T04:59:59Z"),ZoneOffset.UTC));
        create("exit-before-midnight",1);
        Clock expired=Clock.fixed(Instant.parse("2099-01-01T05:00:00Z"),ZoneOffset.UTC);
        exits=new JdbcExits(fixture.jdbc,expired); read=new JdbcInventoryRead(fixture.jdbc,expired);
        assertEquals("LOT_EXPIRED",assertThrows(InventoryFailure.class,() -> create("exit-expired",1)).code());
        var result=stock(Map.of("expired",List.of("true"))).data().get(0);
        assertTrue(result.expired()); assertEquals(9,result.onHandQuantity()); assertEquals(0,result.availableQuantity());
        assertEquals(0,stock(Map.of("expired",List.of("false"))).meta().total());
    }
    @Test void failureAfterExitWritesRollsBackAllEffects() {
        assertThrows(IllegalStateException.class,() -> fixture.tx.execute(s -> {
            exits.create(fixture.actor,"exit-rollback",new NewExit(entry.lotId(),3,"Entrega"),"exit-trace");
            throw new IllegalStateException("Synthetic failure after exit writes");
        }));
        assertEquals(10,fixture.balance(entry.lotId())); assertEquals(1,fixture.events(entry.lotId()));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT count(*) FROM inventory.stock_exit WHERE lot_id=?",Integer.class,entry.lotId()));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT count(*) FROM inventory.exit_request WHERE actor=?",Integer.class,fixture.actor));
    }
    @Test void competingExitsCannotOversellLot() throws Exception {
        var ready=new CountDownLatch(2); var pool=Executors.newFixedThreadPool(2);
        try {
            var tasks=new ArrayList<Future<String>>();
            for(int i=0;i<2;i++) {
                String key="exit-race-"+i;
                tasks.add(pool.submit(() -> {
                    ready.countDown();
                    if(!ready.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Race setup timeout");
                    try { create(key,7); return "OK"; } catch(InventoryFailure e) { return e.code(); }
                }));
            }
            var outcomes=new ArrayList<String>();
            for(var task:tasks) outcomes.add(task.get(30,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(outcomes,"OK")); assertEquals(1,Collections.frequency(outcomes,"INSUFFICIENT_STOCK"));
            assertEquals(3,fixture.balance(entry.lotId())); assertEquals(2,fixture.events(entry.lotId()));
        } finally { pool.shutdownNow(); }
    }
    @Test void stockSeparatesPendingFromAvailableAndHonorsFilters() {
        fixture.prepare("prepare-pending",new NewEntry(entry.medicationId(),entry.supplierId(),entry.lotNumber(),entry.expiresOn(),4));
        var result=stock(Map.of()).data().get(0);
        assertEquals(10,result.onHandQuantity()); assertEquals(10,result.availableQuantity()); assertEquals(4,result.pendingQuantity());
        assertEquals(1,stock(Map.of("medicationId",List.of(entry.medicationId().toString()),"supplierId",List.of(entry.supplierId().toString()))).meta().total());
        assertEquals(0,stock(Map.of("medicationId",List.of(UUID.randomUUID().toString()))).meta().total());
        var empty=stock(Map.of("page",List.of("2"),"limit",List.of("1")));
        assertEquals(1,empty.meta().total()); assertTrue(empty.data().isEmpty());
    }
    @Test void movementPaginationAndCivilDateRange() {
        create("exit-history",2);
        var filters=Map.of("from",List.of("2026-10-11"),"to",List.of("2026-10-11"),"limit",List.of("1"));
        var first=readTx.execute(s -> read.movements(query(filters,true)));
        assertEquals(2,first.meta().total()); assertEquals(1,first.data().size());
        var secondFilters=new HashMap<>(filters); secondFilters.put("page",List.of("2"));
        var second=readTx.execute(s -> read.movements(query(secondFilters,true)));
        assertNotEquals(first.data().get(0).id(),second.data().get(0).id());
        assertEquals(Set.of("ENTRY","EXIT"),Set.of(first.data().get(0).type(),second.data().get(0).type()));
        var yesterday=readTx.execute(s -> read.movements(query(Map.of("to",List.of("2026-10-10")),true)));
        assertEquals(0,yesterday.meta().total());
        assertEquals("repeatable read",readTx.execute(s -> fixture.jdbc.queryForObject("SHOW transaction_isolation",String.class)));
    }
}
