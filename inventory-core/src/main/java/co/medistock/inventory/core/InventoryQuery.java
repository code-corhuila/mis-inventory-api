package co.medistock.inventory.core;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

public record InventoryQuery(int page, int limit, UUID medicationId, UUID supplierId, UUID lotId,
                             Boolean expired, LocalDate from, LocalDate to) {
    public static InventoryQuery parse(Map<String,List<String>> values, boolean movements) {
        var allowed = new HashSet<>(Set.of("page","limit","medicationId","supplierId","lotId"));
        allowed.addAll(movements ? Set.of("from","to") : Set.of("expired"));
        values.forEach((key,list) -> {
            if (!allowed.contains(key) || list.size()!=1 || list.get(0).isEmpty()) throw InventoryFailure.invalid(key);
        });
        int page=number(values,"page",1,1000000), limit=number(values,"limit",20,100);
        String flag=value(values,"expired");
        if (flag!=null && !Set.of("true","false").contains(flag)) throw InventoryFailure.invalid("expired");
        LocalDate from=date(values,"from"), to=date(values,"to");
        if (from!=null && to!=null && from.isAfter(to)) throw InventoryFailure.invalid("to");
        return new InventoryQuery(page,limit,id(values,"medicationId"),id(values,"supplierId"),id(values,"lotId"),
            flag==null ? null : Boolean.valueOf(flag),from,to);
    }
    public long offset() { return ((long)page-1)*limit; }
    private static String value(Map<String,List<String>> values,String key) {
        return values.containsKey(key) ? values.get(key).get(0) : null;
    }
    private static UUID id(Map<String,List<String>> values,String key) {
        String value=value(values,key); return value==null ? null : NewEntry.uuid(value,key);
    }
    private static int number(Map<String,List<String>> values,String key,int fallback,int max) {
        String value=value(values,key);
        if(value==null) return fallback;
        try {
            if(!value.matches("[1-9][0-9]*")) throw InventoryFailure.invalid(key);
            int parsed=Integer.parseInt(value);
            if(parsed>max) throw InventoryFailure.invalid(key);
            return parsed;
        } catch(NumberFormatException e) { throw InventoryFailure.invalid(key); }
    }
    private static LocalDate date(Map<String,List<String>> values,String key) {
        String value=value(values,key);
        if(value==null) return null;
        try {
            if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw InventoryFailure.invalid(key);
            LocalDate date=LocalDate.parse(value);
            if(date.getYear()<1 || date.getYear()>9999) throw InventoryFailure.invalid(key);
            return date;
        } catch(DateTimeParseException e) { throw InventoryFailure.invalid(key); }
    }
}
