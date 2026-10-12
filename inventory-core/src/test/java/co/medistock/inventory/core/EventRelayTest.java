package co.medistock.inventory.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventRelayTest {
    final UUID id=UUID.randomUUID();
    StockEvent event() {
        var body=new HashMap<String,Object>();
        body.put("eventId",id.toString()); body.put("movementId",id.toString());
        for(String key:List.of("lotId","medicationId","supplierId","entryId")) body.put(key,id.toString());
        body.put("exitId",null); body.put("eventType","inventory.stock.changed"); body.put("schemaVersion",1);
        body.put("sequence",1L); body.put("occurredAt","2026-10-11T12:00:00Z"); body.put("traceId","relay-test");
        body.put("movementType","ENTRY"); body.put("expiresOn","2099-01-01");
        body.put("quantity",10); body.put("delta",10); body.put("onHandQuantity",10);
        return new StockEvent(body);
    }
    class Store implements Outbox {
        Optional<Lease> candidate=Optional.of(new Lease(event(),UUID.randomUUID(),1));
        long delay; int acknowledgements,retries; boolean owned=true;
        public Optional<Lease> claim() { return candidate; }
        public boolean acknowledge(Lease lease) { acknowledgements++; return owned; }
        public boolean retry(Lease lease,long seconds) { retries++; delay=seconds; return owned; }
    }
    @Test void idleDoesNotPublishOrUpdate() {
        var store=new Store(); store.candidate=Optional.empty();
        assertEquals(EventRelay.Result.IDLE,new EventRelay(store,e -> { fail("Must not publish"); return null; }).runOnce());
        assertEquals(0,store.acknowledgements+store.retries);
    }
    @Test void matchingAckAloneMarksDelivered() {
        var store=new Store();
        assertEquals(EventRelay.Result.DELIVERED,new EventRelay(store,e -> new EventRelay.Acknowledgement(id,1,true)).runOnce());
        assertEquals(1,store.acknowledgements); assertEquals(0,store.retries);
        store.owned=false;
        assertEquals(EventRelay.Result.LEASE_LOST,new EventRelay(store,e -> new EventRelay.Acknowledgement(id,1,true)).runOnce());
    }
    @Test void invalidAckRetriesWithoutDelivery() {
        for(var ack:Arrays.asList(null,new EventRelay.Acknowledgement(UUID.randomUUID(),1,true),
            new EventRelay.Acknowledgement(id,2,true),new EventRelay.Acknowledgement(id,1,false))) {
            var store=new Store();
            assertEquals(EventRelay.Result.RETRY,new EventRelay(store,e -> ack).runOnce());
            assertEquals(0,store.acknowledgements); assertEquals(1,store.delay);
        }
    }
    @Test void failedPublishHasBoundedBackoffAndDoesNotHideLostLease() {
        for(int attempt:new int[]{1,2,9,10,Integer.MAX_VALUE}) {
            var store=new Store(); store.candidate=Optional.of(new Outbox.Lease(event(),UUID.randomUUID(),attempt));
            assertEquals(EventRelay.Result.RETRY,new EventRelay(store,e -> { throw new IllegalStateException("Synthetic network failure"); }).runOnce());
            assertEquals(attempt==1?1:attempt==2?2:attempt==9?256:300,store.delay);
            assertEquals(0,store.acknowledgements);
        }
        var store=new Store(); store.owned=false;
        assertEquals(EventRelay.Result.LEASE_LOST,new EventRelay(store,e -> null).runOnce());
    }
}
