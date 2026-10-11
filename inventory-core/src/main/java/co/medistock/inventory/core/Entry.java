package co.medistock.inventory.core;

import java.time.*;
import java.util.UUID;

public record Entry(UUID id, UUID lotId, UUID medicationId, UUID supplierId, String lotNumber,
    LocalDate expiresOn, int quantity, String status, UUID createdBy, Instant createdAt) { }
