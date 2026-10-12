package co.medistock.inventory.adapters;

import co.medistock.inventory.core.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcStockEvents implements StockEvents {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper=JsonMapper.builder().build();
    public JdbcStockEvents(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override @Transactional
    public Result apply(UUID actor,StockEvent event) {
        if(actor==null) throw InventoryFailure.invalid("actor");
        String json=mapper.writeValueAsString(new TreeMap<>(event.payload()));
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        var source=jdbc.queryForList("SELECT event_id FROM inventory.outbox WHERE event_id=?",event.eventId());
        if(source.isEmpty()) throw new InventoryFailure(404,"NOT_FOUND","Evento inexistente.",null);
        Boolean matches=jdbc.queryForObject("SELECT payload=?::jsonb FROM inventory.outbox WHERE event_id=?",Boolean.class,json,event.eventId());
        if(!Boolean.TRUE.equals(matches)) throw conflict();
        // El lote existe antes del checkpoint: este bloqueo serializa tambien su creacion.
        jdbc.queryForObject("SELECT id FROM inventory.lot WHERE id=? FOR UPDATE",UUID.class,event.lotId());
        var prior=jdbc.queryForList("SELECT payload_hash FROM inventory.stock_event_inbox WHERE event_id=?",event.eventId());
        if(!prior.isEmpty()) {
            if(!hash.equals(prior.get(0).get("payload_hash"))) throw conflict();
            return new Result(event.eventId(),event.sequence(),false);
        }
        int changed=jdbc.update("INSERT INTO inventory.stock_event_checkpoint(lot_id,last_sequence) VALUES (?,?) ON CONFLICT(lot_id) DO UPDATE SET last_sequence=excluded.last_sequence WHERE inventory.stock_event_checkpoint.last_sequence<excluded.last_sequence",
            event.lotId(),event.sequence());
        boolean applied=changed==1;
        jdbc.update("INSERT INTO inventory.stock_event_inbox(event_id,lot_id,sequence,payload_hash,applied,processed_by) VALUES (?,?,?,?,?,?)",
            event.eventId(),event.lotId(),event.sequence(),hash,applied,actor);
        return new Result(event.eventId(),event.sequence(),applied);
    }
    private InventoryFailure conflict() { return new InventoryFailure(409,"EVENT_CONFLICT","Evento no coincide con su origen.",null); }
}
