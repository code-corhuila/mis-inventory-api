package co.medistock.inventory.core;

import java.time.Instant;
import java.util.UUID;

public interface Exits {
    record Exit(UUID id,UUID lotId,UUID medicationId,UUID supplierId,String lotNumber,int quantity,
                String reason,long remainingQuantity,UUID createdBy,Instant createdAt) {}
    record Result(Exit exit,boolean created) {}
    Result create(UUID actor,String key,NewExit request,String trace);
    Exit find(UUID id);
}
