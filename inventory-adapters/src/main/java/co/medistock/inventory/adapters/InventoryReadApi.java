package co.medistock.inventory.adapters;

import co.medistock.inventory.core.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class InventoryReadApi {
    private final InventoryRead inventory;
    public InventoryReadApi(InventoryRead inventory) { this.inventory=inventory; }
    @GetMapping("/api/v1/stock") @PreAuthorize("hasAnyRole('ADMIN','INVENTORY','SUPERVISOR','CONSULTATION')")
    public InventoryRead.Page<InventoryRead.Stock> stock(@RequestParam MultiValueMap<String,String> params) {
        return inventory.stock(InventoryQuery.parse(params,false));
    }
    @GetMapping("/api/v1/movements") @PreAuthorize("hasAnyRole('ADMIN','INVENTORY','SUPERVISOR')")
    public InventoryRead.Page<InventoryRead.Movement> movements(@RequestParam MultiValueMap<String,String> params) {
        return inventory.movements(InventoryQuery.parse(params,true));
    }
}
