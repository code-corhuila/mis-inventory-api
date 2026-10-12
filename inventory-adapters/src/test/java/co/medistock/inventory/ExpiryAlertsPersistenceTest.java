package co.medistock.inventory;

import co.medistock.inventory.adapters.*;
import co.medistock.inventory.core.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="INV_DB_URL",matches=".+")
class ExpiryAlertsPersistenceTest {
    final EntriesPersistenceTest fixture=new EntriesPersistenceTest();
    final Instant near=Instant.parse("2098-12-31T12:00:00Z");
    JdbcExpiryAlerts alerts;
    Entry entry;
    @BeforeEach void setup() {
        fixture.setup(); entry=fixture.prepare("expiry-prepare",fixture.request).entry();
        fixture.transition("expiry-activate",entry.id(),"activate"); at(near);
    }
    void at(Instant instant) { alerts=new JdbcExpiryAlerts(fixture.jdbc,Clock.fixed(instant,ZoneOffset.UTC)); }
    Optional<ExpiryAlerts.Alert> evaluate(int days) { return fixture.tx.execute(s -> alerts.evaluate(fixture.actor,entry.lotId(),days)); }
    int count(UUID lot) { return fixture.jdbc.queryForObject("SELECT count(*) FROM inventory.expiry_alert WHERE lot_id=?",Integer.class,lot); }
    @Test void repeatedEvaluationPreservesIdentityAndDoesNotChangeStockOrEvents() {
        var first=evaluate(30).orElseThrow(); var replay=evaluate(30).orElseThrow();
        assertEquals(first,replay); assertEquals(1,count(entry.lotId())); assertEquals("OPEN",first.state());
        assertEquals("EXPIRING",first.severity()); assertEquals(10,first.onHandQuantity());
        assertEquals(fixture.actor,first.evaluatedBy()); assertEquals(near,first.firstDetectedAt());
        assertEquals(10,fixture.balance(entry.lotId())); assertEquals(1,fixture.events(entry.lotId()));
        assertEquals(1,fixture.jdbc.queryForObject("SELECT count(*) FROM inventory.movement WHERE lot_id=?",Integer.class,entry.lotId()));
    }
    @Test void readerFiltersAndPaginatesPersistedSnapshotsWithoutMutation() {
        var alert=evaluate(30).orElseThrow(); var reader=new JdbcExpiryAlertRead(fixture.jdbc);
        var tx=new org.springframework.transaction.support.TransactionTemplate(fixture.tx.getTransactionManager());
        tx.setReadOnly(true); tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.execute(s -> {
            assertEquals("repeatable read",fixture.jdbc.queryForObject("SHOW transaction_isolation",String.class));
            var page=reader.list(new ExpiryQuery(1,1,entry.lotId(),"OPEN","EXPIRING"));
            assertEquals(List.of(alert),page.data()); assertEquals(1,page.meta().total());
            var second=reader.list(new ExpiryQuery(2,1,entry.lotId(),"OPEN","EXPIRING"));
            assertTrue(second.data().isEmpty()); assertEquals(1,second.meta().total());
            assertEquals(0,reader.list(new ExpiryQuery(1,1,entry.lotId(),null,"EXPIRED")).meta().total()); return null;
        });
        fixture.tx.execute(s -> new JdbcExits(fixture.jdbc,Clock.fixed(near,ZoneOffset.UTC))
            .create(fixture.actor,"expiry-read-exit",new NewExit(entry.lotId(),10,"Synthetic"),"expiry-trace"));
        var resolved=evaluate(30).orElseThrow();
        assertEquals(List.of(resolved),tx.execute(s -> reader.list(new ExpiryQuery(1,20,entry.lotId(),"RESOLVED",null))).data());
        assertEquals(1,count(entry.lotId()));
    }
    @Test void bogotaMidnightChangesSeverityButKeepsFirstDetection() {
        at(Instant.parse("2099-01-01T04:59:59Z")); var first=evaluate(30).orElseThrow();
        assertEquals("EXPIRING",first.severity()); assertEquals(LocalDate.of(2098,12,31),first.evaluatedOn());
        at(Instant.parse("2099-01-01T05:00:00Z")); var expired=evaluate(30).orElseThrow();
        assertEquals("EXPIRED",expired.severity()); assertEquals(LocalDate.of(2099,1,1),expired.evaluatedOn());
        assertEquals(first.id(),expired.id()); assertEquals(first.firstDetectedAt(),expired.firstDetectedAt());
    }
    @Test void actualExitResolvesAlertWithoutDeletingEvidence() {
        var first=evaluate(30).orElseThrow();
        var exits=new JdbcExits(fixture.jdbc,Clock.fixed(near,ZoneOffset.UTC));
        fixture.tx.execute(s -> exits.create(fixture.actor,"expiry-empty-stock",new NewExit(entry.lotId(),10,"Synthetic"),"expiry-trace"));
        var resolved=evaluate(30).orElseThrow(); assertEquals("RESOLVED",resolved.state()); assertEquals(0,resolved.onHandQuantity());
        assertEquals(first.id(),resolved.id()); assertEquals(first.firstDetectedAt(),resolved.firstDetectedAt());
        assertEquals(near,resolved.resolvedAt()); assertEquals(resolved,evaluate(30).orElseThrow());
        assertEquals(0,fixture.balance(entry.lotId())); assertEquals(2,fixture.events(entry.lotId()));
    }
    @Test void horizonChangesResolveAndReopenTheSameRecordAndPendingStockCreatesNoAlert() {
        at(Instant.parse("2098-12-01T12:00:00Z")); assertTrue(evaluate(30).isEmpty());
        var first=evaluate(365).orElseThrow(); assertEquals("RESOLVED",evaluate(30).orElseThrow().state());
        var reopened=evaluate(365).orElseThrow(); assertEquals(first.id(),reopened.id()); assertNull(reopened.resolvedAt());
        var pending=fixture.prepare("expiry-only-pending",new NewEntry(entry.medicationId(),entry.supplierId(),UUID.randomUUID().toString(),entry.expiresOn(),4)).entry();
        assertTrue(fixture.tx.execute(s -> alerts.evaluate(fixture.actor,pending.lotId(),365)).isEmpty()); assertEquals(0,count(pending.lotId()));
    }
    @Test void resolvedReevaluationWithLaterClockKeepsOriginalAudit() {
        var first=evaluate(30).orElseThrow();
        fixture.tx.execute(s -> new JdbcExits(fixture.jdbc,Clock.fixed(near,ZoneOffset.UTC))
            .create(fixture.actor,"expiry-later-resolution",new NewExit(entry.lotId(),10,"Synthetic"),"expiry-trace"));
        var resolved=evaluate(30).orElseThrow(); at(near.plusSeconds(60));
        var replay=evaluate(30).orElseThrow(); assertEquals(first.id(),replay.id());
        assertEquals(first.firstDetectedAt(),replay.firstDetectedAt()); assertEquals(resolved.resolvedAt(),replay.resolvedAt());
        assertEquals(near.plusSeconds(60),replay.lastEvaluatedAt()); assertEquals("RESOLVED",replay.state());
        assertEquals(0,fixture.balance(entry.lotId())); assertEquals(2,fixture.events(entry.lotId()));
    }
    @Test void rejectsMissingIdentityLotAndClockRegressionWithoutMutation() {
        assertEquals(400,assertThrows(InventoryFailure.class,() -> fixture.tx.execute(s -> alerts.evaluate(null,entry.lotId(),30))).status());
        assertEquals(400,assertThrows(InventoryFailure.class,() -> evaluate(0)).status());
        assertEquals(404,assertThrows(InventoryFailure.class,() -> fixture.tx.execute(s -> alerts.evaluate(fixture.actor,UUID.randomUUID(),30))).status());
        var first=evaluate(30).orElseThrow(); at(near.minusSeconds(1));
        assertEquals("EVALUATION_CLOCK_REGRESSED",assertThrows(InventoryFailure.class,() -> evaluate(30)).code());
        at(near); assertEquals(first,evaluate(30).orElseThrow());
    }
    @Test void failedTransactionRollsBackAlertOnly() {
        assertThrows(IllegalStateException.class,() -> fixture.tx.execute(s -> {
            alerts.evaluate(fixture.actor,entry.lotId(),30); throw new IllegalStateException("Synthetic failure");
        }));
        assertEquals(0,count(entry.lotId())); assertEquals(10,fixture.balance(entry.lotId())); assertEquals(1,fixture.events(entry.lotId()));
    }
    @Test void concurrentEvaluatorsKeepOneStableAlert() throws Exception {
        var pool=Executors.newFixedThreadPool(2); var ready=new CountDownLatch(2);
        try {
            var jobs=new ArrayList<Future<ExpiryAlerts.Alert>>();
            for(int i=0;i<2;i++) jobs.add(pool.submit(() -> { ready.countDown(); assertTrue(ready.await(10,TimeUnit.SECONDS)); return evaluate(30).orElseThrow(); }));
            assertEquals(jobs.get(0).get(30,TimeUnit.SECONDS).id(),jobs.get(1).get(30,TimeUnit.SECONDS).id());
            assertEquals(1,count(entry.lotId())); assertEquals(10,fixture.balance(entry.lotId()));
        } finally { pool.shutdownNow(); }
    }
    @Test void evaluationWaitsForExitCommitAndReadsTheNewBalance() throws Exception {
        evaluate(30); var pool=Executors.newFixedThreadPool(2); var written=new CountDownLatch(1); var release=new CountDownLatch(1); var started=new CountDownLatch(1);
        try {
            var exit=pool.submit(() -> fixture.tx.execute(s -> {
                new JdbcExits(fixture.jdbc,Clock.fixed(near,ZoneOffset.UTC)).create(fixture.actor,"expiry-locked-exit",new NewExit(entry.lotId(),10,"Synthetic"),"expiry-trace");
                written.countDown(); try { assertTrue(release.await(10,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new IllegalStateException(e); } return true;
            }));
            assertTrue(written.await(10,TimeUnit.SECONDS)); var evaluation=pool.submit(() -> { started.countDown(); return evaluate(30).orElseThrow(); });
            assertTrue(started.await(10,TimeUnit.SECONDS)); assertThrows(TimeoutException.class,() -> evaluation.get(100,TimeUnit.MILLISECONDS));
            release.countDown(); assertTrue(exit.get(30,TimeUnit.SECONDS)); var result=evaluation.get(30,TimeUnit.SECONDS);
            assertEquals("RESOLVED",result.state()); assertEquals(0,result.onHandQuantity()); assertEquals(1,count(entry.lotId()));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
