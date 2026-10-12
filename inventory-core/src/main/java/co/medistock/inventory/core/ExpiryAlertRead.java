package co.medistock.inventory.core;

public interface ExpiryAlertRead {
    InventoryRead.Page<ExpiryAlerts.Alert> list(ExpiryQuery query);
}
