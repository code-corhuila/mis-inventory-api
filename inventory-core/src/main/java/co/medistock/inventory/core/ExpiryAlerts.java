package co.medistock.inventory.core;

import java.time.*;
import java.util.*;

public interface ExpiryAlerts {
    record Alert(UUID id,UUID lotId,String severity,String state,LocalDate expiresOn,
        long onHandQuantity,LocalDate evaluatedOn,int windowDays,UUID evaluatedBy,
        Instant firstDetectedAt,Instant lastEvaluatedAt,Instant resolvedAt) { }
    Optional<Alert> evaluate(UUID actor,UUID lotId,int windowDays);
}
