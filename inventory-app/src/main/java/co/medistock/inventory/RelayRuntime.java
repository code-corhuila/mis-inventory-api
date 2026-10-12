package co.medistock.inventory;

import co.medistock.inventory.adapters.*;
import co.medistock.inventory.core.EventRelay;
import com.zaxxer.hikari.*;
import java.nio.file.Files;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.transaction.support.TransactionTemplate;

public final class RelayRuntime {
    private static final org.slf4j.Logger LOG=LoggerFactory.getLogger(RelayRuntime.class);
    private RelayRuntime() { }
    public static void verifyLogin(JdbcTemplate jdbc) {
        var identity=jdbc.queryForMap("SELECT current_user AS username,rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls FROM pg_roles WHERE rolname=current_user");
        if(!"inventory_relay_app".equals(identity.get("username"))) throw new IllegalStateException("Exclusive relay login required");
        for(String flag:List.of("rolsuper","rolcreatedb","rolcreaterole","rolreplication","rolbypassrls"))
            if(!Boolean.FALSE.equals(identity.get(flag))) throw new IllegalStateException("Administrative relay login rejected");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_has_role(current_user,'inventory_relay','MEMBER')",Boolean.class)))
            throw new IllegalStateException("Relay capability membership required");
        for(String table:List.of("lot","entry","movement","request","stock_exit","exit_request","stock_event_inbox","stock_event_checkpoint")) {
            for(String privilege:List.of("SELECT","INSERT","UPDATE")) {
                if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_any_column_privilege(current_user,?::text,?::text)",Boolean.class,"inventory."+table,privilege)))
                    throw new IllegalStateException("Cross-effect relay privilege rejected");
            }
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,?::text,'DELETE')",Boolean.class,"inventory."+table)))
                throw new IllegalStateException("Cross-effect relay privilege rejected");
        }
        for(String privilege:List.of("INSERT","DELETE"))
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,'inventory.outbox',?::text)",Boolean.class,privilege)))
                throw new IllegalStateException("Immutable outbox privilege rejected");
        for(String column:List.of("payload","event_id","sequence","lot_id"))
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_column_privilege(current_user,'inventory.outbox',?::text,'UPDATE')",Boolean.class,column)))
                throw new IllegalStateException("Immutable outbox privilege rejected");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,'inventory.outbox','SELECT')",Boolean.class)))
            throw new IllegalStateException("Outbox read capability required");
        for(String column:List.of("status","attempts","next_attempt_at","lease_token","leased_until","delivered_at"))
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_column_privilege(current_user,'inventory.outbox',?::text,'UPDATE')",Boolean.class,column)))
                throw new IllegalStateException("Delivery metadata capability required");
    }
    public static void run(RelaySettings settings) {
        RSAPublicKey key;
        try(var input=Files.newInputStream(settings.credentials().resolve("public.pem"))) { key=RsaKeyConverters.x509().convert(input); }
        catch(Exception invalid) { throw new IllegalArgumentException("Cannot load relay public key"); }
        var publisher=new HttpEventPublisher(settings.workerOrigin(),settings.credentials().resolve("service.jwt"),key,
            settings.keyId(),settings.environment(),settings.subject());
        publisher.validateCredential();
        var config=new HikariConfig();
        config.setJdbcUrl(settings.databaseUrl()+"?connectTimeout=3&socketTimeout=5");
        config.setUsername("inventory_relay_app"); config.setPassword(settings.password());
        config.setMaximumPoolSize(2); config.setMinimumIdle(0); config.setConnectionTimeout(2000); config.setPoolName("inventory-relay");
        Thread main=Thread.currentThread();
        Thread shutdown=new Thread(() -> {
            main.interrupt(); try { main.join(20000); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        },"relay-shutdown");
        try(var source=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(source); jdbc.setQueryTimeout(5); verifyLogin(jdbc);
            var outbox=new JdbcOutbox(jdbc,new TransactionTemplate(new DataSourceTransactionManager(source)));
            var relay=new EventRelay(outbox,event -> {
                LOG.info("relay eventId={} traceId={}",event.eventId(),event.payload().get("traceId"));
                return publisher.publish(event);
            });
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                while(!main.isInterrupted()) {
                    try {
                        var result=relay.runOnce(); if(result!=EventRelay.Result.IDLE) LOG.info("relay result={}",result);
                    }
                    catch(RuntimeException unavailable) { LOG.warn("relay database unavailable; delivery not confirmed"); }
                    try { Thread.sleep(1000); } catch(InterruptedException interrupted) { main.interrupt(); }
                }
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch(IllegalStateException shuttingDown) { }
            }
        }
    }
}
