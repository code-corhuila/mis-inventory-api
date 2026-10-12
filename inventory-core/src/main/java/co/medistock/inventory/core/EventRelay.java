package co.medistock.inventory.core;

import java.util.UUID;

public final class EventRelay {
    public record Acknowledgement(UUID eventId,long sequence,boolean accepted) {}
    public interface Publisher { Acknowledgement publish(StockEvent event); }
    public enum Result { IDLE, DELIVERED, RETRY, LEASE_LOST }
    private final Outbox outbox;
    private final Publisher publisher;
    public EventRelay(Outbox outbox,Publisher publisher) { this.outbox=outbox; this.publisher=publisher; }
    public Result runOnce() {
        var candidate=outbox.claim();
        if(candidate.isEmpty()) return Result.IDLE;
        var lease=candidate.get();
        Acknowledgement ack;
        try { ack=publisher.publish(lease.event()); }
        catch(RuntimeException unavailable) { return retry(lease); }
        if(ack==null || !lease.event().eventId().equals(ack.eventId()) ||
            lease.event().sequence()!=ack.sequence() || !ack.accepted()) return retry(lease);
        return outbox.acknowledge(lease)?Result.DELIVERED:Result.LEASE_LOST;
    }
    private Result retry(Outbox.Lease lease) {
        long delay=Math.min(300,1L<<Math.min(9,Math.max(0,lease.attempt()-1)));
        return outbox.retry(lease,delay)?Result.RETRY:Result.LEASE_LOST;
    }
}
