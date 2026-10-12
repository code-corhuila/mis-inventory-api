package co.medistock.inventory.core;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExpiryPolicyTest {
    final LocalDate today=LocalDate.of(2026,10,12);
    @Test void windowIsInclusiveAndExpiryDayIsExpired() {
        assertEquals("EXPIRING",ExpiryPolicy.classify(today,today.plusDays(30),1,30).orElseThrow());
        assertTrue(ExpiryPolicy.classify(today,today.plusDays(31),1,30).isEmpty());
        assertEquals("EXPIRED",ExpiryPolicy.classify(today,today,1,30).orElseThrow());
        assertEquals("EXPIRED",ExpiryPolicy.classify(today,today.minusDays(1),1,30).orElseThrow());
    }
    @Test void zeroUnitsDoNotCreateAnAlertAndLeapCalendarIsReal() {
        assertTrue(ExpiryPolicy.classify(today,today,0,30).isEmpty());
        assertEquals("EXPIRING",ExpiryPolicy.classify(LocalDate.of(2028,2,28),LocalDate.of(2028,2,29),1,1).orElseThrow());
        assertEquals("EXPIRING",ExpiryPolicy.classify(today,today.plusDays(365),1,365).orElseThrow());
    }
    @Test void rejectsInvalidPolicyInputs() {
        for(int days:new int[]{0,-1,366}) assertEquals(400,assertThrows(InventoryFailure.class,() -> ExpiryPolicy.classify(today,today,1,days)).status());
        assertThrows(InventoryFailure.class,() -> ExpiryPolicy.classify(today,today,-1,30));
        assertThrows(InventoryFailure.class,() -> ExpiryPolicy.classify(null,today,1,30));
    }
}
