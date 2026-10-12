package co.medistock.inventory.adapters;

import co.medistock.inventory.core.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

public final class JdbcOutbox implements Outbox {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper mapper=JsonMapper.builder().build();
    public JdbcOutbox(JdbcTemplate jdbc,TransactionTemplate transaction) {
        this.jdbc=jdbc;
        this.transaction=new TransactionTemplate(transaction.getTransactionManager());
        this.transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(5);
    }
    @Override public Optional<Lease> claim() {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Relay cannot run inside an enclosing database transaction");
        return transaction.execute(s -> {
            var rows=jdbc.query("""
                WITH candidate AS (
                    SELECT event_id FROM inventory.outbox
                    WHERE status='PENDING' AND next_attempt_at<=clock_timestamp()
                        AND (leased_until IS NULL OR leased_until<=clock_timestamp())
                    ORDER BY next_attempt_at,sequence FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE inventory.outbox o SET lease_token=?,leased_until=clock_timestamp()+interval '30 seconds',
                    attempts=LEAST(o.attempts::bigint+1,2147483647)::integer
                FROM candidate c WHERE o.event_id=c.event_id
                RETURNING o.payload::text,o.lease_token,o.attempts
                """,(r,n) -> new Lease(new StockEvent(mapper.readValue(r.getString(1),
                    new tools.jackson.core.type.TypeReference<Map<String,Object>>(){})),r.getObject(2,UUID.class),r.getInt(3)),UUID.randomUUID());
            return rows.stream().findFirst();
        });
    }
    @Override public boolean acknowledge(Lease lease) {
        return Boolean.TRUE.equals(transaction.execute(s -> jdbc.update("""
            UPDATE inventory.outbox SET status='DELIVERED',delivered_at=clock_timestamp(),lease_token=NULL,leased_until=NULL
            WHERE event_id=? AND sequence=? AND status='PENDING' AND lease_token=? AND leased_until>clock_timestamp()
            """,lease.event().eventId(),lease.event().sequence(),lease.token())==1));
    }
    @Override public boolean retry(Lease lease,long delaySeconds) {
        if(delaySeconds<1 || delaySeconds>300) throw new IllegalArgumentException("Retry delay must be 1..300 seconds");
        return Boolean.TRUE.equals(transaction.execute(s -> jdbc.update("""
            UPDATE inventory.outbox SET next_attempt_at=clock_timestamp()+make_interval(secs=>?),lease_token=NULL,leased_until=NULL
            WHERE event_id=? AND sequence=? AND status='PENDING' AND lease_token=? AND leased_until>clock_timestamp()
            """,delaySeconds,lease.event().eventId(),lease.event().sequence(),lease.token())==1));
    }
}
