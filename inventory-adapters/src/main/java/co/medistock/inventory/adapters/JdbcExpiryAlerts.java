package co.medistock.inventory.adapters;

import co.medistock.inventory.core.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcExpiryAlerts implements ExpiryAlerts {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public JdbcExpiryAlerts(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    private record Lot(LocalDate expires,long onHand) { }
    private List<Alert> alerts(UUID lotId) {
        return jdbc.query("SELECT * FROM inventory.expiry_alert WHERE lot_id=? FOR UPDATE",(r,n) -> map(r),lotId);
    }
    static Alert map(ResultSet r) throws SQLException {
        Timestamp resolved=r.getTimestamp("resolved_at");
        return new Alert(r.getObject("id",UUID.class),r.getObject("lot_id",UUID.class),r.getString("severity"),r.getString("state"),
            r.getObject("expires_on",LocalDate.class),r.getLong("on_hand_quantity"),r.getObject("evaluated_on",LocalDate.class),
            r.getInt("window_days"),r.getObject("evaluated_by",UUID.class),r.getTimestamp("first_detected_at").toInstant(),
            r.getTimestamp("last_evaluated_at").toInstant(),resolved==null?null:resolved.toInstant());
    }
    @Override @Transactional
    public Optional<Alert> evaluate(UUID actor,UUID lotId,int windowDays) {
        if(actor==null) throw InventoryFailure.invalid("actor");
        if(lotId==null) throw InventoryFailure.invalid("lotId");
        ExpiryPolicy.window(windowDays);
        // El mismo bloqueo usado por las salidas evita evaluar un saldo anterior a su commit.
        var lots=jdbc.query("SELECT expires_on,available FROM inventory.lot WHERE id=? FOR UPDATE",
            (r,n) -> new Lot(r.getObject("expires_on",LocalDate.class),r.getLong("available")),lotId);
        if(lots.isEmpty()) throw new InventoryFailure(404,"NOT_FOUND","Lote inexistente.",null);
        Lot lot=lots.get(0); var prior=alerts(lotId); Alert old=prior.isEmpty()?null:prior.get(0);
        Instant now=clock.instant(); LocalDate today=now.atZone(NewEntry.ZONE).toLocalDate();
        if(old!=null && now.isBefore(old.lastEvaluatedAt())) throw new InventoryFailure(409,"EVALUATION_CLOCK_REGRESSED","Reloj anterior a la ultima evaluacion.",null);
        var severity=ExpiryPolicy.classify(today,lot.expires(),lot.onHand(),windowDays);
        if(severity.isEmpty() && old==null) return Optional.empty();
        String state=severity.isPresent()?"OPEN":"RESOLVED";
        String kind=severity.orElseGet(() -> old.severity());
        Instant resolved=severity.isPresent()?null:old.resolvedAt()==null?now:old.resolvedAt();
        jdbc.update("""
            INSERT INTO inventory.expiry_alert(id,lot_id,severity,state,expires_on,on_hand_quantity,
                evaluated_on,window_days,evaluated_by,first_detected_at,last_evaluated_at,resolved_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(lot_id) DO UPDATE SET
                severity=excluded.severity,state=excluded.state,expires_on=excluded.expires_on,
                on_hand_quantity=excluded.on_hand_quantity,evaluated_on=excluded.evaluated_on,
                window_days=excluded.window_days,evaluated_by=excluded.evaluated_by,
                last_evaluated_at=excluded.last_evaluated_at,resolved_at=excluded.resolved_at
            """,UUID.randomUUID(),lotId,kind,state,lot.expires(),lot.onHand(),today,windowDays,actor,
            Timestamp.from(now),Timestamp.from(now),resolved==null?null:Timestamp.from(resolved));
        return Optional.of(alerts(lotId).get(0));
    }
}
