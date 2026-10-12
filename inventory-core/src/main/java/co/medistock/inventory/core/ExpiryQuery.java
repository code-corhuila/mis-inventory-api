package co.medistock.inventory.core;

import java.util.*;

public record ExpiryQuery(int page,int limit,UUID lotId,String state,String severity) {
    public static ExpiryQuery parse(Map<String,List<String>> values) {
        var base=new HashMap<String,List<String>>();
        values.forEach((key,list) -> {
            if(!Set.of("page","limit","lotId","state","severity").contains(key) || list.size()!=1 || list.get(0).isEmpty()) throw InventoryFailure.invalid(key);
            if(Set.of("page","limit","lotId").contains(key)) base.put(key,list);
        });
        var pagination=InventoryQuery.parse(base,false);
        String state=values.containsKey("state")?values.get("state").get(0):null;
        String severity=values.containsKey("severity")?values.get("severity").get(0):null;
        if(state!=null && !Set.of("OPEN","RESOLVED").contains(state)) throw InventoryFailure.invalid("state");
        if(severity!=null && !Set.of("EXPIRING","EXPIRED").contains(severity)) throw InventoryFailure.invalid("severity");
        return new ExpiryQuery(pagination.page(),pagination.limit(),pagination.lotId(),state,severity);
    }
    public long offset() { return ((long)page-1)*limit; }
}
