package co.medistock.inventory.core;

import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class NewEntryTest {
    final UUID medication=UUID.randomUUID(),supplier=UUID.randomUUID();
    final LocalDate date=LocalDate.of(2026,10,11);
    @Test void normalizesLot() { assertEquals("ABC-12",new NewEntry(medication,supplier," abc-12 ",date,10).lotNumber()); }
    @ParameterizedTest @ValueSource(ints={-1,0,1000001,Integer.MAX_VALUE})
    void rejectsQuantity(int value) { assertThrows(InventoryFailure.class,()->new NewEntry(medication,supplier,"ABC",date,value)); }
    @ParameterizedTest @ValueSource(strings={"","a b","\u00e1","a/b","\u00df"})
    void rejectsLot(String value) { assertThrows(InventoryFailure.class,()->new NewEntry(medication,supplier,value,date,10)); }
    @ParameterizedTest @ValueSource(strings={"2026-02-30","2026-1-01","2026-10-11T00:00:00Z","0000-01-01"})
    void rejectsDate(String value) {
        assertThrows(InventoryFailure.class,()->new NewEntry(medication,supplier,"ABC",NewEntry.date(value),10));
    }
    @Test void bogotaBoundary() {
        var entry=new NewEntry(medication,supplier,"ABC",date,10);
        assertDoesNotThrow(()->entry.ensureFresh(Clock.fixed(Instant.parse("2026-10-11T04:59:59Z"),ZoneOffset.UTC)));
        assertEquals(422,assertThrows(InventoryFailure.class,()->entry.ensureFresh(
            Clock.fixed(Instant.parse("2026-10-11T05:00:00Z"),ZoneOffset.UTC))).status());
    }
    @ParameterizedTest @ValueSource(strings={"short","spaces not allowed","a/b/c/d/e"})
    void rejectsKey(String value) { assertThrows(InventoryFailure.class,()->NewEntry.key(value)); }
    @Test void acceptsKey() { assertDoesNotThrow(()->NewEntry.key("entry-key-001")); }
    @Test void requiresCanonicalUuid() {
        UUID id=UUID.fromString("abcdefab-1234-4234-8234-abcdefabcdef");
        assertThrows(InventoryFailure.class,()->NewEntry.uuid(id.toString().toUpperCase(java.util.Locale.ROOT),"id"));
        assertEquals(id,NewEntry.uuid(id.toString(),"id"));
    }
    @Test void validatesEventTrace() {
        for (String trace : new String[]{null,"","bad/value","x".repeat(129)}) {
            assertThrows(InventoryFailure.class,()->NewEntry.trace(trace));
        }
        assertDoesNotThrow(()->NewEntry.trace("trace-outbox-001"));
    }
}
