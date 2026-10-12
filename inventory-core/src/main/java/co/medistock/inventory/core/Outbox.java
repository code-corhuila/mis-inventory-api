package co.medistock.inventory.core;

import java.util.*;

public interface Outbox {
    record Lease(StockEvent event,UUID token,int attempt) {}
    Optional<Lease> claim();
    boolean acknowledge(Lease lease);
    boolean retry(Lease lease,long delaySeconds);
}
