package co.medistock.inventory.core;

import java.time.LocalDate;
import java.util.Optional;

public final class ExpiryPolicy {
    private ExpiryPolicy() { }
    public static void window(int days) {
        if(days<1 || days>365) throw InventoryFailure.invalid("windowDays");
    }
    public static Optional<String> classify(LocalDate today,LocalDate expires,long onHand,int days) {
        window(days);
        if(today==null || expires==null) throw InventoryFailure.invalid("date");
        if(onHand<0) throw InventoryFailure.invalid("onHandQuantity");
        if(onHand==0 || expires.isAfter(today.plusDays(days))) return Optional.empty();
        return Optional.of(expires.isAfter(today)?"EXPIRING":"EXPIRED");
    }
}
