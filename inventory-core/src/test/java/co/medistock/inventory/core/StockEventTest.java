package co.medistock.inventory.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StockEventTest {
    Map<String,Object> sample() {
        String id="11111111-1111-4111-8111-111111111111";
        var data=new HashMap<String,Object>();
        data.putAll(Map.of("eventId",id,"movementId",id,"lotId",id,"medicationId",id,"supplierId",id,
            "eventType","inventory.stock.changed","schemaVersion",1,"sequence",1,"occurredAt","2026-10-11T12:00:00Z","traceId","event-test"));
        data.putAll(Map.of("movementType","ENTRY","expiresOn","2099-01-01","quantity",10,"delta",10,"onHandQuantity",10,"entryId",id));
        data.put("exitId",null); return data;
    }
    @Test void acceptsEntryExitAndPreservesImmutablePayload() {
        var body=sample(); var event=new StockEvent(body);
        body.put("quantity",11); assertEquals(10,event.payload().get("quantity"));
        assertThrows(UnsupportedOperationException.class,() -> event.payload().put("quantity",20));
        body=sample(); body.put("movementType","EXIT"); body.put("delta",-10); body.put("exitId",body.get("entryId")); body.put("entryId",null);
        assertEquals(1,new StockEvent(body).sequence());
    }
    @Test void rejectsMalformedFieldsAndDeltaIdentityMismatch() {
        for(var patch:List.of(Map.of("sequence",1.0),Map.of("quantity",true),Map.of("quantity","10"),Map.of("delta",9),
            Map.of("schemaVersion",2),Map.of("sequence",0),Map.of("unknown",true),Map.of("occurredAt","yesterday"),
            Map.of("expiresOn","0000-01-01"),Map.of("movementId","22222222-2222-4222-8222-222222222222"))) {
            var body=sample(); body.putAll(patch);
            assertThrows(InventoryFailure.class,() -> new StockEvent(body));
        }
        for(String key:sample().keySet()) {
            var body=sample(); body.remove(key); assertThrows(InventoryFailure.class,() -> new StockEvent(body));
        }
    }
}
