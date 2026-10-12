package co.medistock.inventory.adapters;

import co.medistock.inventory.core.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class StockEventApi {
    private final StockEvents events;
    public StockEventApi(StockEvents events) { this.events=events; }
    @PostMapping("/api/v1/internal/events/stock-changed")
    @PreAuthorize("hasRole('INVENTORY') and authentication.token.getClaimAsString('service') == 'mis-worker'")
    public StockEvents.Result apply(@AuthenticationPrincipal Jwt jwt,@RequestBody Map<String,Object> body) {
        return events.apply(UUID.fromString(jwt.getSubject()),new StockEvent(body));
    }
}
