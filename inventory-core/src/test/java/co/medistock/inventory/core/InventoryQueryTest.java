package co.medistock.inventory.core;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryQueryTest {
    private InventoryQuery query(String key,String value,boolean movements) {
        return InventoryQuery.parse(Map.of(key,List.of(value)),movements);
    }
    @Test void defaults() {
        var query=InventoryQuery.parse(Map.of(),false);
        assertEquals(1,query.page()); assertEquals(20,query.limit()); assertEquals(0,query.offset());
    }
    @Test void paginationIsBounded() {
        for(String value:List.of("0","-1","1.5"," 1","01","1000001","2147483648",""))
            assertThrows(InventoryFailure.class,() -> query("page",value,false));
        assertThrows(InventoryFailure.class,() -> query("limit","101",false));
        assertEquals(99999900,InventoryQuery.parse(Map.of("page",List.of("1000000"),"limit",List.of("100")),false).offset());
    }
    @Test void filtersUseCanonicalIds() {
        UUID id=UUID.randomUUID(); assertEquals(id,query("lotId",id.toString(),false).lotId());
        for(String field:List.of("lotId","medicationId","supplierId"))
            assertThrows(InventoryFailure.class,() -> query(field,"not-a-uuid",true));
    }
    @Test void expiredUsesStrictBooleans() {
        assertTrue(query("expired","true",false).expired()); assertFalse(query("expired","false",false).expired());
        assertThrows(InventoryFailure.class,() -> query("expired","TRUE",false));
        assertThrows(InventoryFailure.class,() -> query("expired","true",true));
    }
    @Test void rejectsUnknownAndRepeatedFields() {
        assertThrows(InventoryFailure.class,() -> query("sort","name",false));
        assertThrows(InventoryFailure.class,() -> InventoryQuery.parse(Map.of("page",List.of("1","2")),false));
    }
    @Test void validatesCalendarAndRange() {
        assertEquals(LocalDate.of(2026,10,10),query("from","2026-10-10",true).from());
        for(String value:List.of("2026-02-30","0000-01-01","2026-1-1","yesterday"))
            assertThrows(InventoryFailure.class,() -> query("to",value,true));
        assertThrows(InventoryFailure.class,() -> InventoryQuery.parse(Map.of("from",List.of("2026-10-11"),"to",List.of("2026-10-10")),true));
        assertThrows(InventoryFailure.class,() -> query("from","2026-10-10",false));
    }
}
