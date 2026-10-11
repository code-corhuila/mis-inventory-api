package co.medistock.inventory.adapters;

import co.medistock.inventory.core.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcEntries implements Entries {
    private final JdbcTemplate jdbc;
    private final References references;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final String SELECT = "SELECT e.*, l.medication_id,l.supplier_id,l.lot_number,l.expires_on FROM inventory.entry e JOIN inventory.lot l ON l.id=e.lot_id WHERE e.id=?";
    private final RowMapper<Entry> row = (r,n) -> new Entry(r.getObject("id",UUID.class),r.getObject("lot_id",UUID.class),
        r.getObject("medication_id",UUID.class),r.getObject("supplier_id",UUID.class),r.getString("lot_number"),
        r.getObject("expires_on",LocalDate.class),r.getInt("quantity"),r.getString("status"),
        r.getObject("created_by",UUID.class),r.getTimestamp("created_at").toInstant());
    public JdbcEntries(JdbcTemplate jdbc, References references, Clock clock) { this.jdbc=jdbc; this.references=references; this.clock=clock; }
    private void lock(String value) {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?,0))",Integer.class,value);
    }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private Entry previous(UUID actor,String action,String key,String hash) {
        NewEntry.key(key); lock(actor+":"+action+":"+key);
        var results = jdbc.queryForList("SELECT request_hash,response_json FROM inventory.request WHERE actor=? AND operation=? AND request_key=?",actor,action,key);
        if (results.isEmpty()) return null;
        if (!results.get(0).get("request_hash").equals(hash)) throw failure(409,"IDEMPOTENCY_CONFLICT","Clave usada con otros datos.",null);
        return mapper.readValue(results.get(0).get("response_json").toString(),Entry.class);
    }
    private void remember(UUID actor,String action,String key,String hash,Entry entry) {
        jdbc.update("INSERT INTO inventory.request(actor,operation,request_key,request_hash,response_json,entry_id) VALUES (?,?,?,?,?,?)",
            actor,action,key,hash,mapper.writeValueAsString(entry),entry.id());
    }
    @Override @Transactional
    public Result prepare(UUID actor,String key,NewEntry request,String token,String trace) {
        String hash = hash(request);
        Entry previous = previous(actor,"prepare",key,hash);
        if (previous != null) return new Result(previous,false);
        request.ensureFresh(clock); references.verify(request.medicationId(),request.supplierId(),token,trace);
        lock("lot:"+request.medicationId()+":"+request.supplierId()+":"+request.lotNumber());
        var lots = jdbc.queryForList("SELECT id,expires_on FROM inventory.lot WHERE medication_id=? AND supplier_id=? AND lot_number=?",
            request.medicationId(),request.supplierId(),request.lotNumber());
        UUID lotId;
        if (lots.isEmpty()) {
            lotId = UUID.randomUUID();
            jdbc.update("INSERT INTO inventory.lot(id,medication_id,supplier_id,lot_number,expires_on) VALUES (?,?,?,?,?)",
                lotId,request.medicationId(),request.supplierId(),request.lotNumber(),request.expiresOn());
        } else {
            lotId = (UUID) lots.get(0).get("id");
            if (!lots.get(0).get("expires_on").toString().equals(request.expiresOn().toString())) {
                throw failure(422,"BUSINESS_RULE_VIOLATION","El lote tiene otro vencimiento.","expiresOn");
            }
        }
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO inventory.entry(id,lot_id,quantity,created_by,created_at) VALUES (?,?,?,?,?)",
            id,lotId,request.quantity(),actor,java.sql.Timestamp.from(clock.instant()));
        Entry entry=find(id); remember(actor,"prepare",key,hash,entry); return new Result(entry,true);
    }
    @Override @Transactional(readOnly=true)
    public Entry find(UUID id) {
        var entries=jdbc.query(SELECT,row,id);
        if(entries.isEmpty()) throw failure(404,"NOT_FOUND","Entrada inexistente.",null);
        return entries.get(0);
    }
    @Override @Transactional
    public Entry transition(UUID actor,String key,UUID id,String action,String trace) {
        NewEntry.trace(trace);
        if (!Set.of("activate","cancel").contains(action)) throw InventoryFailure.invalid("action");
        String hash=hash(id.toString()); Entry previous=previous(actor,action,key,hash);
        if(previous!=null) return previous;
        var found=jdbc.query(SELECT+" FOR UPDATE OF e",row,id);
        if(found.isEmpty()) throw failure(404,"NOT_FOUND","Entrada inexistente.",null);
        Entry entry=found.get(0);
        String target=action.equals("activate") ? "ACTIVE" : "CANCELLED";
        if(!entry.status().equals(target)) {
            if(!entry.status().equals("PENDING")) throw failure(422,"BUSINESS_RULE_VIOLATION","Transicion incompatible.",null);
            if(action.equals("activate")) {
                new NewEntry(entry.medicationId(),entry.supplierId(),entry.lotNumber(),entry.expiresOn(),entry.quantity()).ensureFresh(clock);
                int changed=jdbc.update("UPDATE inventory.lot SET available=available+? WHERE id=? AND available<=2147483647-?",
                    entry.quantity(),entry.lotId(),entry.quantity());
                if(changed!=1) throw failure(422,"BUSINESS_RULE_VIOLATION","Limite de stock excedido.",null);
                jdbc.update("INSERT INTO inventory.movement(id,entry_id,lot_id,quantity,actor,created_at,trace_id) VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID(),id,entry.lotId(),entry.quantity(),actor,java.sql.Timestamp.from(clock.instant()),trace);
            }
            jdbc.update("UPDATE inventory.entry SET status=?,changed_by=?,changed_at=? WHERE id=?",target,actor,java.sql.Timestamp.from(clock.instant()),id);
        }
        Entry result=find(id); remember(actor,action,key,hash,result); return result;
    }
    private InventoryFailure failure(int status,String code,String message,String field) { return new InventoryFailure(status,code,message,field); }
}
