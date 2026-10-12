package co.medistock.inventory.adapters;

import co.medistock.inventory.core.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class ExpiryAlertApi {
    public record Evaluation(UUID lotId,boolean alertPresent,ExpiryAlerts.Alert alert) { }
    private final ExpiryAlerts alerts;
    private final ExpiryAlertRead read;
    public ExpiryAlertApi(ExpiryAlerts alerts,ExpiryAlertRead read) { this.alerts=alerts; this.read=read; }
    @PostMapping("/api/v1/internal/alerts/expiry/evaluate")
    @PreAuthorize("hasRole('INVENTORY') and authentication.token.getClaimAsString('service') == 'mis-worker'")
    public Evaluation evaluate(@AuthenticationPrincipal Jwt jwt,@RequestBody Map<String,Object> body) {
        if(!body.keySet().equals(Set.of("lotId","windowDays"))) throw InventoryFailure.invalid("body");
        if(!(body.get("lotId") instanceof String id) || !(body.get("windowDays") instanceof Integer days)) throw InventoryFailure.invalid("body");
        UUID lot=NewEntry.uuid(id,"lotId"); ExpiryPolicy.window(days);
        var result=alerts.evaluate(UUID.fromString(jwt.getSubject()),lot,days);
        return new Evaluation(lot,result.isPresent(),result.orElse(null));
    }
    @GetMapping("/api/v1/alerts/expiry")
    @PreAuthorize("hasAnyRole('ADMIN','INVENTORY','SUPERVISOR') and authentication.token.getClaimAsString('service') == null")
    public InventoryRead.Page<ExpiryAlerts.Alert> list(@RequestParam MultiValueMap<String,String> params) {
        return read.list(ExpiryQuery.parse(params));
    }
}
