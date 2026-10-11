package co.medistock.inventory.core;

import java.util.UUID;

public record NewExit(UUID lotId,int quantity,String reason) {
    public NewExit {
        if(lotId==null) throw InventoryFailure.invalid("lotId");
        if(quantity<1 || quantity>1000000) throw InventoryFailure.invalid("quantity");
        if(reason==null) throw InventoryFailure.invalid("reason");
        reason=reason.strip();
        if(reason.isEmpty() || reason.codePointCount(0,reason.length())>200 || reason.codePoints().anyMatch(Character::isISOControl))
            throw InventoryFailure.invalid("reason");
    }
}
