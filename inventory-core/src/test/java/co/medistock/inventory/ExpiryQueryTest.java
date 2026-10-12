package co.medistock.inventory;

import co.medistock.inventory.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExpiryQueryTest {
    @Test void defaultsAndBoundedPagination() {
        assertEquals(new ExpiryQuery(1,20,null,null,null),ExpiryQuery.parse(Map.of()));
        UUID lot=UUID.randomUUID();
        var query=ExpiryQuery.parse(Map.of("page",List.of("1000000"),"limit",List.of("100"),"lotId",List.of(lot.toString()),
            "state",List.of("OPEN"),"severity",List.of("EXPIRING")));
        assertEquals(99999900L,query.offset()); assertEquals(lot,query.lotId());
    }
    @Test void rejectsUnknownRepeatedEmptyAndInvalidFilters() {
        for(var values:List.of(Map.of("unknown",List.of("x")),Map.of("state",List.of("OPEN","RESOLVED")),
            Map.of("state",List.of("")),Map.of("state",List.of("open")),Map.of("severity",List.of("OTHER")),
            Map.of("lotId",List.of("bad")),Map.of("limit",List.of("101")),Map.of("page",List.of("0"))))
            assertEquals(400,assertThrows(InventoryFailure.class,() -> ExpiryQuery.parse(values)).status());
    }
}
