package co.medistock.inventory.core;

import java.time.*;
import java.util.*;

public interface InventoryRead {
    record Meta(int page,int limit,long total) {}
    record Page<T>(List<T> data,Meta meta) {}
    record Stock(UUID lotId,UUID medicationId,UUID supplierId,String lotNumber,LocalDate expiresOn,
                 long onHandQuantity,long availableQuantity,long pendingQuantity,boolean expired) {}
    record Movement(UUID id,UUID entryId,UUID exitId,UUID lotId,UUID medicationId,UUID supplierId,String lotNumber,
                    String type,int quantity,UUID actor,Instant createdAt) {}
    Page<Stock> stock(InventoryQuery query);
    Page<Movement> movements(InventoryQuery query);
}
