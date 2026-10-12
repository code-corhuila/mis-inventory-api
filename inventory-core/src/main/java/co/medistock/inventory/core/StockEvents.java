package co.medistock.inventory.core;

import java.util.UUID;

public interface StockEvents {
    record Result(UUID eventId,long sequence,boolean applied) {}
    Result apply(UUID actor,StockEvent event);
}
