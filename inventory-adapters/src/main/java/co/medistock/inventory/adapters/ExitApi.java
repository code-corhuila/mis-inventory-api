package co.medistock.inventory.adapters;

import co.medistock.inventory.core.*;
import java.net.URI;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class ExitApi {
    public record Request(String lotId,Integer quantity,String reason) {
        NewExit normalized() {
            if(quantity==null) throw InventoryFailure.invalid("quantity");
            return new NewExit(NewEntry.uuid(lotId,"lotId"),quantity,reason);
        }
    }
    private final Exits exits;
    public ExitApi(Exits exits) { this.exits=exits; }
    @PostMapping("/api/v1/exits") @PreAuthorize("hasAnyRole('ADMIN','INVENTORY') and authentication.token.getClaimAsString('service') == null")
    public ResponseEntity<Exits.Exit> create(@AuthenticationPrincipal Jwt jwt,
        @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody Request body,HttpServletRequest request) {
        var result=exits.create(UUID.fromString(jwt.getSubject()),key,body.normalized(),request.getAttribute("traceId").toString());
        return ResponseEntity.status(result.created()?201:200).location(URI.create("/api/v1/exits/"+result.exit().id())).body(result.exit());
    }
    @GetMapping("/api/v1/exits/{id}") @PreAuthorize("hasAnyRole('ADMIN','INVENTORY','SUPERVISOR')")
    public Exits.Exit find(@PathVariable String id) { return exits.find(NewEntry.uuid(id,"id")); }
}
