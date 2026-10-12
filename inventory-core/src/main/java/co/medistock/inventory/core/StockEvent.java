package co.medistock.inventory.core;

import java.time.*;
import java.util.*;

public record StockEvent(Map<String,Object> payload) {
    private static final Set<String> FIELDS=Set.of("eventId","eventType","schemaVersion","sequence","occurredAt","traceId",
        "movementId","movementType","lotId","medicationId","supplierId","expiresOn","quantity","delta","onHandQuantity","entryId","exitId");
    public StockEvent {
        if(payload==null || !payload.keySet().equals(FIELDS)) throw InventoryFailure.invalid("body");
        payload=Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        for(String field:List.of("eventId","movementId","lotId","medicationId","supplierId")) NewEntry.uuid(text(payload,field),field);
        if(!text(payload,"eventId").equals(text(payload,"movementId"))) throw InventoryFailure.invalid("movementId");
        if(!text(payload,"eventType").equals("inventory.stock.changed")) throw InventoryFailure.invalid("eventType");
        integer(payload,"schemaVersion",1,1); integer(payload,"sequence",1,Long.MAX_VALUE);
        long quantity=integer(payload,"quantity",1,1000000), delta=integer(payload,"delta",-1000000,1000000);
        integer(payload,"onHandQuantity",0,2147483647);
        String type=text(payload,"movementType");
        if(!Set.of("ENTRY","EXIT").contains(type)) throw InventoryFailure.invalid("movementType");
        String origin=type.equals("ENTRY")?"entryId":"exitId", absent=type.equals("ENTRY")?"exitId":"entryId";
        NewEntry.uuid(text(payload,origin),origin);
        if(payload.get(absent)!=null || delta!=(type.equals("ENTRY")?quantity:-quantity)) throw InventoryFailure.invalid("delta");
        NewEntry.trace(text(payload,"traceId"));
        try { Instant.parse(text(payload,"occurredAt")); }
        catch(DateTimeException e) { throw InventoryFailure.invalid("occurredAt"); }
        LocalDate date=NewEntry.date(text(payload,"expiresOn"));
        if(date.getYear()<1 || date.getYear()>9999) throw InventoryFailure.invalid("expiresOn");
    }
    private static String text(Map<String,Object> data,String field) {
        if(!(data.get(field) instanceof String value)) throw InventoryFailure.invalid(field);
        return value;
    }
    private static long integer(Map<String,Object> data,String field,long min,long max) {
        Object value=data.get(field);
        if(!(value instanceof Integer || value instanceof Long)) throw InventoryFailure.invalid(field);
        long number=((Number)value).longValue();
        if(number<min || number>max) throw InventoryFailure.invalid(field);
        return number;
    }
    public UUID eventId() { return UUID.fromString((String)payload.get("eventId")); }
    public UUID lotId() { return UUID.fromString((String)payload.get("lotId")); }
    public long sequence() { return ((Number)payload.get("sequence")).longValue(); }
}
