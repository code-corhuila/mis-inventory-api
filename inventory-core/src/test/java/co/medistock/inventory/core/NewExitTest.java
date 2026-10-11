package co.medistock.inventory.core;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NewExitTest {
    @Test void normalizesReason() { assertEquals("Entrega",new NewExit(UUID.randomUUID(),1,"  Entrega  ").reason()); }
    @Test void requiresLot() { assertThrows(InventoryFailure.class,() -> new NewExit(null,1,"Entrega")); }
    @Test void boundsQuantity() {
        for(int quantity:new int[]{-1,0,1000001}) assertThrows(InventoryFailure.class,() -> new NewExit(UUID.randomUUID(),quantity,"Entrega"));
        assertEquals(1000000,new NewExit(UUID.randomUUID(),1000000,"Entrega").quantity());
    }
    @Test void requiresBoundedReason() {
        for(String reason:new String[]{null,"","   ","a".repeat(201),"entre\nga","entre\u0000ga"})
            assertThrows(InventoryFailure.class,() -> new NewExit(UUID.randomUUID(),1,reason));
        assertEquals(200,new NewExit(UUID.randomUUID(),1,"a".repeat(200)).reason().length());
    }
}
