package co.medistock.inventory;

import co.medistock.inventory.adapters.*;
import co.medistock.inventory.core.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="INV_DB_URL",matches=".+")
class OutboxPersistenceTest {
    final EntriesPersistenceTest fixture=new EntriesPersistenceTest();
    JdbcOutbox outbox; UUID lot,eventId; Connection guard;
    @BeforeEach void setup() throws Exception {
        fixture.setup(); outbox=new JdbcOutbox(fixture.jdbc,fixture.tx);
        var entry=fixture.prepare("prepare-relay",fixture.request).entry(); lot=entry.lotId();
        fixture.transition("activate-relay",entry.id(),"activate");
        eventId=fixture.jdbc.queryForObject("SELECT event_id FROM inventory.outbox WHERE lot_id=?",UUID.class,lot);
        due();
        // Lock unrelated fixture events so claim tests use only their own lot, without rewriting other rows.
        guard=fixture.jdbc.getDataSource().getConnection(); guard.setAutoCommit(false);
        try(var statement=guard.prepareStatement("SELECT event_id FROM inventory.outbox WHERE lot_id<>? FOR UPDATE")) {
            statement.setObject(1,lot); try(var rows=statement.executeQuery()) { while(rows.next()) { } }
        }
    }
    @AfterEach void unlock() throws Exception { if(guard!=null) { try { guard.rollback(); } finally { guard.close(); } } }
    void due() { fixture.jdbc.update("UPDATE inventory.outbox SET next_attempt_at='2000-01-01' WHERE event_id=?",eventId); }
    String status() { return fixture.jdbc.queryForObject("SELECT status FROM inventory.outbox WHERE event_id=?",String.class,eventId); }
    @Test void reservationCommitsAndOnlyValidAckDelivers() {
        var lease=outbox.claim().orElseThrow(); assertEquals(eventId,lease.event().eventId()); assertEquals(1,lease.attempt());
        assertEquals("PENDING",status()); assertTrue(outbox.claim().isEmpty());
        var wrong=new Outbox.Lease(lease.event(),UUID.randomUUID(),1);
        assertFalse(outbox.acknowledge(wrong)); assertFalse(outbox.retry(wrong,1));
        assertTrue(outbox.acknowledge(lease)); assertEquals("DELIVERED",status());
        assertFalse(outbox.acknowledge(lease)); assertTrue(outbox.claim().isEmpty());
        assertEquals(10,fixture.balance(lot)); assertEquals(1,fixture.events(lot));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT count(*) FROM inventory.outbox WHERE event_id=? AND (lease_token IS NOT NULL OR delivered_at IS NULL)",Integer.class,eventId));
    }
    @Test void expiredLeaseReclaimsAfterAbandonedAttemptAndFencesOldWorker() {
        var abandoned=outbox.claim().orElseThrow();
        fixture.jdbc.update("UPDATE inventory.outbox SET leased_until=clock_timestamp()-interval '1 second' WHERE event_id=?",eventId);
        assertFalse(outbox.acknowledge(abandoned));
        var replacement=new JdbcOutbox(fixture.jdbc,fixture.tx).claim().orElseThrow();
        assertNotEquals(abandoned.token(),replacement.token()); assertEquals(2,replacement.attempt());
        assertFalse(outbox.retry(abandoned,1)); assertFalse(outbox.acknowledge(abandoned));
        assertTrue(outbox.acknowledge(replacement)); assertEquals("DELIVERED",status());
    }
    @Test void retryClearsLeaseAndDefersAttemptWithoutChangingPayload() {
        String before=fixture.jdbc.queryForObject("SELECT payload::text FROM inventory.outbox WHERE event_id=?",String.class,eventId);
        var lease=outbox.claim().orElseThrow(); assertTrue(outbox.retry(lease,300));
        assertTrue(outbox.claim().isEmpty()); assertEquals("PENDING",status());
        assertEquals(before,fixture.jdbc.queryForObject("SELECT payload::text FROM inventory.outbox WHERE event_id=?",String.class,eventId));
        assertEquals(1,fixture.jdbc.queryForObject("SELECT count(*) FROM inventory.outbox WHERE event_id=? AND lease_token IS NULL AND next_attempt_at>clock_timestamp()",Integer.class,eventId));
        assertThrows(IllegalArgumentException.class,() -> outbox.retry(lease,301));
        due(); assertEquals(2,outbox.claim().orElseThrow().attempt());
    }
    @Test void concurrentRelaysReserveOnce() throws Exception {
        var pool=Executors.newFixedThreadPool(2); var ready=new CountDownLatch(2);
        try {
            var tasks=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++) tasks.add(pool.submit(() -> {
                ready.countDown(); if(!ready.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Race timeout");
                return outbox.claim().isPresent();
            }));
            int claims=0; for(var task:tasks) if(task.get(30,TimeUnit.SECONDS)) claims++;
            assertEquals(1,claims);
        } finally { pool.shutdownNow(); }
    }
    @Test void lostConfirmationRetriesExistingOwnerReceiptOutsideTransaction() {
        var owner=new JdbcStockEvents(fixture.jdbc); UUID worker=UUID.randomUUID();
        var relay=new EventRelay(outbox,event -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(fixture.tx.execute(s -> owner.apply(worker,event)).applied());
            throw new IllegalStateException("Synthetic lost confirmation after committed owner receipt");
        });
        assertEquals(EventRelay.Result.RETRY,relay.runOnce()); assertEquals("PENDING",status()); due();
        var restarted=new EventRelay(new JdbcOutbox(fixture.jdbc,fixture.tx),event -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(fixture.tx.execute(s -> owner.apply(worker,event)).applied());
            return new EventRelay.Acknowledgement(event.eventId(),event.sequence(),true);
        });
        assertEquals(EventRelay.Result.DELIVERED,restarted.runOnce()); assertEquals("DELIVERED",status());
        assertEquals(1,fixture.jdbc.queryForObject("SELECT count(*) FROM inventory.stock_event_inbox WHERE event_id=?",Integer.class,eventId));
        assertEquals(10,fixture.balance(lot));
    }
    @Test void enclosingTransactionIsRejectedBeforeClaim() {
        assertThrows(IllegalStateException.class,() -> fixture.tx.execute(s -> outbox.claim()));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT attempts FROM inventory.outbox WHERE event_id=?",Integer.class,eventId));
    }
}
