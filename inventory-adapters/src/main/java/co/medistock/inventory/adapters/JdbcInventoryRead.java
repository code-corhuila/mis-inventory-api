package co.medistock.inventory.adapters;

import co.medistock.inventory.core.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class JdbcInventoryRead implements InventoryRead {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public JdbcInventoryRead(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    private static class Filter {
        String where=" WHERE true";
        final List<Object> args=new ArrayList<>();
        void add(String clause,Object value) { where+=" AND "+clause; args.add(value); }
    }
    private Filter filters(InventoryQuery query) {
        var filter=new Filter();
        if(query.medicationId()!=null) filter.add("l.medication_id=?",query.medicationId());
        if(query.supplierId()!=null) filter.add("l.supplier_id=?",query.supplierId());
        if(query.lotId()!=null) filter.add("l.id=?",query.lotId());
        return filter;
    }
    private <T> Page<T> page(String source,String columns,String order,Filter filter,InventoryQuery query,RowMapper<T> row) {
        long total=jdbc.queryForObject("SELECT count(*)"+source+filter.where,Long.class,filter.args.toArray());
        var args=new ArrayList<>(filter.args); args.add(query.limit()); args.add(query.offset());
        var data=jdbc.query("SELECT "+columns+source+filter.where+" ORDER BY "+order+" LIMIT ? OFFSET ?",row,args.toArray());
        return new Page<>(data,new Meta(query.page(),query.limit(),total));
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<Stock> stock(InventoryQuery query) {
        LocalDate today=LocalDate.now(clock.withZone(NewEntry.ZONE));
        var filter=filters(query);
        if(query.expired()!=null) filter.add(query.expired()?"l.expires_on<=?":"l.expires_on>?",today);
        String source=" FROM inventory.lot l LEFT JOIN (SELECT lot_id,sum(quantity) pending FROM inventory.entry WHERE status='PENDING' GROUP BY lot_id) e ON e.lot_id=l.id";
        return page(source,"l.*,coalesce(e.pending,0) pending","l.expires_on ASC,l.id ASC",filter,query,(r,n) -> {
            LocalDate expires=r.getObject("expires_on",LocalDate.class);
            long onHand=r.getLong("available"); boolean expired=!expires.isAfter(today);
            return new Stock(r.getObject("id",UUID.class),r.getObject("medication_id",UUID.class),r.getObject("supplier_id",UUID.class),
                r.getString("lot_number"),expires,onHand,expired?0:onHand,r.getLong("pending"),expired);
        });
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<Movement> movements(InventoryQuery query) {
        var filter=filters(query);
        if(query.from()!=null) filter.add("m.created_at>=?",Timestamp.from(query.from().atStartOfDay(NewEntry.ZONE).toInstant()));
        if(query.to()!=null) filter.add("m.created_at<?",Timestamp.from(query.to().plusDays(1).atStartOfDay(NewEntry.ZONE).toInstant()));
        return page(" FROM inventory.movement m JOIN inventory.lot l ON l.id=m.lot_id",
            "m.*,l.medication_id,l.supplier_id,l.lot_number","m.created_at DESC,m.id DESC",filter,query,(r,n) ->
                new Movement(r.getObject("id",UUID.class),r.getObject("entry_id",UUID.class),r.getObject("exit_id",UUID.class),r.getObject("lot_id",UUID.class),
                    r.getObject("medication_id",UUID.class),r.getObject("supplier_id",UUID.class),r.getString("lot_number"),
                    r.getObject("entry_id")==null?"EXIT":"ENTRY",r.getInt("quantity"),r.getObject("actor",UUID.class),r.getTimestamp("created_at").toInstant()));
    }
}
