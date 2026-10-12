package co.medistock.inventory;

import co.medistock.inventory.adapters.*;
import co.medistock.inventory.core.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="INV_RELAY_DB_PASSWORD",matches=".+")
class RelayLoginPersistenceTest {
    JdbcTemplate jdbc;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("INV_DB_URL"),"inventory_relay_app",System.getenv("INV_RELAY_DB_PASSWORD")));
    }
    @Test void exclusiveLoginPassesFullGuardAndCanClaimOrIdle() {
        assertDoesNotThrow(() -> RelayRuntime.verifyLogin(jdbc));
        var api=new JdbcTemplate(new DriverManagerDataSource(System.getenv("INV_DB_URL"),"inventory_app",System.getenv("INV_DB_PASSWORD")));
        var tx=new TransactionTemplate(new DataSourceTransactionManager(api.getDataSource()));
        var references=new References("http://catalog","http://suppliers") {
            @Override public void verify(UUID medication,UUID supplier,String token,String trace) { }
        };
        var entries=new JdbcEntries(api,references,Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"),ZoneOffset.UTC));
        UUID actor=UUID.randomUUID();
        var request=new NewEntry(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID().toString(),LocalDate.of(2099,1,1),10);
        var entry=tx.execute(s -> entries.prepare(actor,"prepare-relay-login",request,"synthetic","relay-login")).entry();
        tx.execute(s -> entries.transition(actor,"activate-relay-login",entry.id(),"activate","relay-login"));
        api.update("UPDATE inventory.outbox SET next_attempt_at='1990-01-01' WHERE lot_id=?",entry.lotId());
        var outbox=new JdbcOutbox(jdbc,new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())));
        var lease=outbox.claim().orElseThrow(); assertEquals(entry.lotId(),lease.event().lotId());
        assertTrue(outbox.retry(lease,1));
        assertEquals(10,api.queryForObject("SELECT available FROM inventory.lot WHERE id=?",Integer.class,entry.lotId()));
    }
    @Test void realLoginCannotWriteStockPayloadOrReadInbox() {
        assertEquals("inventory_relay_app",jdbc.queryForObject("SELECT current_user",String.class));
        for(String sql:new String[]{"UPDATE inventory.lot SET available=available","UPDATE inventory.outbox SET payload=payload",
            "SELECT * FROM inventory.stock_event_inbox","SET ROLE inventory_writer"})
            assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.execute(sql));
    }
}
