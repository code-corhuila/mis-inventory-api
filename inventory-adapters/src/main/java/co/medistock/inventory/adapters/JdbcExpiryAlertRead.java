package co.medistock.inventory.adapters;

import co.medistock.inventory.core.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class JdbcExpiryAlertRead implements ExpiryAlertRead {
    private final JdbcTemplate jdbc;
    public JdbcExpiryAlertRead(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public InventoryRead.Page<ExpiryAlerts.Alert> list(ExpiryQuery query) {
        String where=" WHERE true"; var args=new ArrayList<Object>();
        if(query.lotId()!=null) { where+=" AND lot_id=?"; args.add(query.lotId()); }
        if(query.state()!=null) { where+=" AND state=?"; args.add(query.state()); }
        if(query.severity()!=null) { where+=" AND severity=?"; args.add(query.severity()); }
        long total=jdbc.queryForObject("SELECT count(*) FROM inventory.expiry_alert"+where,Long.class,args.toArray());
        args.add(query.limit()); args.add(query.offset());
        var data=jdbc.query("SELECT * FROM inventory.expiry_alert"+where+" ORDER BY expires_on ASC,lot_id ASC LIMIT ? OFFSET ?",(r,n) -> JdbcExpiryAlerts.map(r),args.toArray());
        return new InventoryRead.Page<>(data,new InventoryRead.Meta(query.page(),query.limit(),total));
    }
}
