package co.medistock.inventory.core;

public final class InventoryFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final String field;

    public InventoryFailure(int status, String code, String message, String field) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    public int status() { return status; }
    public String code() { return code; }
    public String field() { return field; }

    public static InventoryFailure invalid(String field) {
        return new InventoryFailure(400, "VALIDATION_ERROR", "Datos invalidos.", field);
    }
}
