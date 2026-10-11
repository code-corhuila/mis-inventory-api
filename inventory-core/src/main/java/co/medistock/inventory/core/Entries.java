package co.medistock.inventory.core;

import java.util.UUID;

public interface Entries {
    record Result(Entry entry, boolean created) { }
    Result prepare(UUID actor, String key, NewEntry request, String token, String trace);
    Entry find(UUID id);
    Entry transition(UUID actor, String key, UUID id, String action, String trace);
}
