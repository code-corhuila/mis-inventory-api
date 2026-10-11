package co.medistock.inventory.core;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

public record NewEntry(UUID medicationId, UUID supplierId, String lotNumber, LocalDate expiresOn, int quantity) {
    public static final ZoneId ZONE = ZoneId.of("America/Bogota");
    public NewEntry {
        if (medicationId == null) throw InventoryFailure.invalid("medicationId");
        if (supplierId == null) throw InventoryFailure.invalid("supplierId");
        if (lotNumber == null) throw InventoryFailure.invalid("lotNumber");
        lotNumber = lotNumber.strip();
        if (!lotNumber.matches("[A-Za-z0-9-]{1,40}")) throw InventoryFailure.invalid("lotNumber");
        lotNumber = lotNumber.toUpperCase(Locale.ROOT);
        if (expiresOn == null || expiresOn.getYear() < 1 || expiresOn.getYear() > 9999) throw InventoryFailure.invalid("expiresOn");
        if (quantity < 1 || quantity > 1000000) throw InventoryFailure.invalid("quantity");
    }
    public void ensureFresh(Clock clock) {
        if (!expiresOn.isAfter(LocalDate.now(clock.withZone(ZONE)))) {
            throw new InventoryFailure(422, "BUSINESS_RULE_VIOLATION", "Lote vencido.", "expiresOn");
        }
    }
    public static UUID uuid(String value, String field) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException e) { throw InventoryFailure.invalid(field); }
    }
    public static LocalDate date(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw InventoryFailure.invalid("expiresOn");
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) { throw InventoryFailure.invalid("expiresOn"); }
    }
    public static void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) throw InventoryFailure.invalid("Idempotency-Key");
    }
    public static void trace(String trace) {
        if (trace == null || !trace.matches("[A-Za-z0-9._:-]{1,128}")) throw InventoryFailure.invalid("X-Correlation-Id");
    }
}
