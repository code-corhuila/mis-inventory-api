package co.medistock.inventory.adapters;

import co.medistock.inventory.core.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcExits implements Exits {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final RowMapper<Exit> row=(r,n) -> new Exit(r.getObject("id",UUID.class),r.getObject("lot_id",UUID.class),
        r.getObject("medication_id",UUID.class),r.getObject("supplier_id",UUID.class),r.getString("lot_number"),
        r.getInt("quantity"),r.getString("reason"),r.getLong("remaining_quantity"),r.getObject("created_by",UUID.class),r.getTimestamp("created_at").toInstant());
    public JdbcExits(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    @Override @Transactional
    public Result create(UUID actor,String key,NewExit request,String trace) {
        NewEntry.trace(trace);
        NewEntry.key(key);
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?,0))",Integer.class,"exit:"+actor+":"+key);
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(request))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        var prior=jdbc.queryForList("SELECT request_hash,response_json FROM inventory.exit_request WHERE actor=? AND request_key=?",actor,key);
        if(!prior.isEmpty()) {
            if(!prior.get(0).get("request_hash").equals(hash)) throw new InventoryFailure(409,"IDEMPOTENCY_CONFLICT","Clave usada con otros datos.",null);
            return new Result(mapper.readValue(prior.get(0).get("response_json").toString(),Exit.class),false);
        }
        // Serializa sobre el lote; vencimiento se evalua despues de adquirir el bloqueo.
        var lots=jdbc.queryForList("SELECT expires_on,available FROM inventory.lot WHERE id=? FOR UPDATE",request.lotId());
        if(lots.isEmpty()) throw new InventoryFailure(404,"NOT_FOUND","Lote inexistente.","lotId");
        LocalDate today=LocalDate.now(clock.withZone(NewEntry.ZONE));
        if(!((java.sql.Date)lots.get(0).get("expires_on")).toLocalDate().isAfter(today))
            throw new InventoryFailure(422,"LOT_EXPIRED","Lote vencido.","lotId");
        long available=((Number)lots.get(0).get("available")).longValue();
        if(available<request.quantity()) throw new InventoryFailure(422,"INSUFFICIENT_STOCK","Stock insuficiente.","quantity");
        int changed=jdbc.update("UPDATE inventory.lot SET available=available-? WHERE id=? AND available>=? AND expires_on>?",
            request.quantity(),request.lotId(),request.quantity(),today);
        if(changed!=1) throw new InventoryFailure(422,"INSUFFICIENT_STOCK","Stock no disponible.","quantity");
        UUID id=UUID.randomUUID(); Instant time=clock.instant();
        jdbc.update("INSERT INTO inventory.stock_exit(id,lot_id,quantity,reason,remaining_quantity,created_by,created_at) VALUES (?,?,?,?,?,?,?)",
            id,request.lotId(),request.quantity(),request.reason(),available-request.quantity(),actor,Timestamp.from(time));
        jdbc.update("INSERT INTO inventory.movement(id,exit_id,lot_id,quantity,actor,created_at,trace_id) VALUES (?,?,?,?,?,?,?)",
            UUID.randomUUID(),id,request.lotId(),request.quantity(),actor,Timestamp.from(time),trace);
        Exit exit=find(id);
        jdbc.update("INSERT INTO inventory.exit_request(actor,request_key,request_hash,response_json,exit_id) VALUES (?,?,?,?,?)",
            actor,key,hash,mapper.writeValueAsString(exit),id);
        return new Result(exit,true);
    }
    @Override @Transactional(readOnly=true)
    public Exit find(UUID id) {
        var found=jdbc.query("SELECT e.*,l.medication_id,l.supplier_id,l.lot_number FROM inventory.stock_exit e JOIN inventory.lot l ON l.id=e.lot_id WHERE e.id=?",row,id);
        if(found.isEmpty()) throw new InventoryFailure(404,"NOT_FOUND","Salida inexistente.",null);
        return found.get(0);
    }
}
