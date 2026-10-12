package co.medistock.inventory.adapters;

import co.medistock.inventory.core.*;
import java.net.URI;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class EntryApi {
    public record Request(String medicationId,String supplierId,String lotNumber,String expiresOn,Integer quantity) {
        NewEntry normalized() {
            if(quantity==null) throw InventoryFailure.invalid("quantity");
            return new NewEntry(NewEntry.uuid(medicationId,"medicationId"),NewEntry.uuid(supplierId,"supplierId"),lotNumber,NewEntry.date(expiresOn),quantity);
        }
    }
    private final Entries entries;
    private final JdbcTemplate jdbc;
    public EntryApi(Entries entries,JdbcTemplate jdbc) { this.entries=entries; this.jdbc=jdbc; }
    @PostMapping("/api/v1/entries") @PreAuthorize("hasAnyRole('ADMIN','INVENTORY') and (authentication.token.getClaimAsString('service') == null or authentication.token.getClaimAsString('service') == 'mis-workflow')")
    public ResponseEntity<Entry> prepare(@AuthenticationPrincipal Jwt jwt,
        @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody Request body,HttpServletRequest request) {
        var result=entries.prepare(UUID.fromString(jwt.getSubject()),key,body.normalized(),jwt.getTokenValue(),request.getAttribute("traceId").toString());
        return ResponseEntity.status(result.created()?201:200).location(URI.create("/api/v1/entries/"+result.entry().id())).body(result.entry());
    }
    @GetMapping("/api/v1/entries/{id}") @PreAuthorize("hasAnyRole('ADMIN','INVENTORY','SUPERVISOR')")
    public Entry find(@PathVariable String id) { return entries.find(NewEntry.uuid(id,"id")); }
    @PostMapping("/api/v1/entries/{id}/{action:activate|cancel}") @PreAuthorize("hasAnyRole('ADMIN','INVENTORY') and (authentication.token.getClaimAsString('service') == null or authentication.token.getClaimAsString('service') == 'mis-workflow')")
    public Entry transition(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@PathVariable String action,
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @RequestBody(required=false) Map<String,Object> body,HttpServletRequest request) {
        if(body!=null && !body.isEmpty()) throw InventoryFailure.invalid("body");
        return entries.transition(UUID.fromString(jwt.getSubject()),key,NewEntry.uuid(id,"id"),action,request.getAttribute("traceId").toString());
    }
    @GetMapping("/health") public Map<String,String> health() {
        jdbc.queryForObject("SELECT count(*) FROM inventory.entry e JOIN inventory.lot l ON l.id=e.lot_id WHERE false",Long.class);
        jdbc.queryForObject("SELECT count(*) FROM inventory.request WHERE false",Long.class);
        jdbc.queryForObject("SELECT count(*) FROM inventory.movement WHERE false",Long.class);
        jdbc.queryForObject("SELECT count(*) FROM inventory.stock_exit WHERE false",Long.class);
        jdbc.queryForObject("SELECT count(*) FROM inventory.exit_request WHERE false",Long.class);
        jdbc.queryForObject("SELECT count(*) FROM inventory.outbox WHERE false",Long.class);
        jdbc.queryForObject("SELECT count(*) FROM inventory.stock_event_inbox WHERE false",Long.class);
        jdbc.queryForObject("SELECT count(*) FROM inventory.stock_event_checkpoint WHERE false",Long.class);
        return Map.of("status","UP");
    }
}
