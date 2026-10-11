package co.medistock.inventory;

import co.medistock.inventory.adapters.*;
import co.medistock.inventory.core.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="INV_DB_URL",matches=".+")
class EntriesPersistenceTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    JdbcEntries entries;
    UUID actor;
    NewEntry request;
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource(System.getenv("INV_DB_URL"),"inventory_app",System.getenv("INV_DB_PASSWORD"));
        jdbc=new JdbcTemplate(source); tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var references=new References("http://catalog","http://suppliers") {
            @Override public void verify(UUID medication,UUID supplier,String token,String trace) { }
        };
        entries=new JdbcEntries(jdbc,references,Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"),ZoneOffset.UTC));
        actor=UUID.randomUUID();
        request=new NewEntry(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID().toString(),LocalDate.of(2099,1,1),10);
        assertEquals("inventory_app",jdbc.queryForObject("SELECT current_user",String.class));
    }
    Entries.Result prepare(String key,NewEntry body) {
        return tx.execute(s -> entries.prepare(actor,key,body,"fixture-token","fixture-trace"));
    }
    Entry transition(String key,UUID id,String action) {
        return tx.execute(s -> entries.transition(actor,key,id,action,"fixture-trace"));
    }
    long balance(UUID lot) { return jdbc.queryForObject("SELECT available FROM inventory.lot WHERE id=?",Long.class,lot); }
    int events(UUID lot) { return jdbc.queryForObject("SELECT count(*) FROM inventory.outbox WHERE lot_id=?",Integer.class,lot); }
    @Test void prepareReplayPreservesOriginalSnapshotAfterActivation() {
        var first=prepare("prepare-001",request); assertTrue(first.created());
        assertEquals(0,balance(first.entry().lotId())); assertEquals(0,events(first.entry().lotId()));
        transition("activate-001",first.entry().id(),"activate");
        var replay=prepare("prepare-001",request);
        assertFalse(replay.created()); assertEquals(first.entry(),replay.entry());
        assertEquals("ACTIVE",entries.find(first.entry().id()).status());
    }
    @Test void activationReplayAndNewKeyDoNotDuplicateStockOrEvent() {
        var pending=prepare("prepare-002",request).entry();
        var active=transition("activate-002",pending.id(),"activate");
        assertEquals(active,transition("activate-002",pending.id(),"activate"));
        assertEquals(active,transition("activate-003",pending.id(),"activate"));
        assertEquals(10,balance(pending.lotId())); assertEquals(1,events(pending.lotId()));
        String query="SELECT (payload->>'delta')||','||(payload->>'onHandQuantity')||','||(payload->>'traceId') FROM inventory.outbox WHERE lot_id=?";
        assertEquals("10,10,fixture-trace",jdbc.queryForObject(query,String.class,pending.lotId()));
        assertEquals(422,assertThrows(InventoryFailure.class,() -> transition("cancel-active",pending.id(),"cancel")).status());
    }
    @Test void cancellationReplayLeavesStockAndOutboxUntouched() {
        var pending=prepare("prepare-004",request).entry();
        assertEquals("CANCELLED",transition("cancel-001",pending.id(),"cancel").status());
        transition("cancel-001",pending.id(),"cancel");
        assertEquals(0,balance(pending.lotId())); assertEquals(0,events(pending.lotId()));
        assertEquals(422,assertThrows(InventoryFailure.class,() -> transition("activate-004",pending.id(),"activate")).status());
    }
    @Test void reusedKeyWithAnotherBodyIsConflict() {
        var first=prepare("prepare-005",request).entry();
        var changed=new NewEntry(request.medicationId(),request.supplierId(),request.lotNumber(),request.expiresOn(),11);
        assertEquals(409,assertThrows(InventoryFailure.class,() -> prepare("prepare-005",changed)).status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM inventory.entry WHERE lot_id=?",Integer.class,first.lotId()));
    }
    @Test void stockOverflowDoesNotCommitTransitionOrIntent() {
        var pending=prepare("prepare-006",request).entry();
        jdbc.update("UPDATE inventory.lot SET available=2147483647 WHERE id=?",pending.lotId());
        assertEquals(422,assertThrows(InventoryFailure.class,() -> transition("activate-overflow",pending.id(),"activate")).status());
        assertEquals("PENDING",entries.find(pending.id()).status()); assertEquals(2147483647L,balance(pending.lotId()));
        assertEquals(0,events(pending.lotId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM inventory.request WHERE actor=? AND operation='activate'",Integer.class,actor));
    }
    @Test void concurrentActivationDoesNotDuplicateStock() throws Exception {
        var pending=prepare("prepare-race",request).entry();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready=new java.util.concurrent.CountDownLatch(2);
        try {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<Entry>>();
            for(int i=0;i<2;i++) {
                String key="activate-race-"+i;
                tasks.add(pool.submit(() -> {
                    ready.countDown();
                    if(!ready.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Race setup timeout");
                    return transition(key,pending.id(),"activate");
                }));
            }
            for(var task:tasks) assertEquals("ACTIVE",task.get(30,java.util.concurrent.TimeUnit.SECONDS).status());
            assertEquals(10,balance(pending.lotId())); assertEquals(1,events(pending.lotId()));
        } finally { pool.shutdownNow(); }
    }
    @Test void failureAfterActivationRollsBackStockMovementEventAndIntent() {
        var pending=prepare("prepare-rollback",request).entry();
        assertThrows(IllegalStateException.class,() -> tx.execute(s -> {
            entries.transition(actor,"activate-rollback",pending.id(),"activate","fixture-trace");
            throw new IllegalStateException("Synthetic failure after all writes");
        }));
        assertEquals("PENDING",entries.find(pending.id()).status());
        assertEquals(0,balance(pending.lotId())); assertEquals(0,events(pending.lotId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM inventory.movement WHERE entry_id=?",Integer.class,pending.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM inventory.request WHERE actor=? AND operation='activate'",Integer.class,actor));
    }
}
