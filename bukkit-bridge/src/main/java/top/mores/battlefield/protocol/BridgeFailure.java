package top.mores.battlefield.protocol;

/** Machine-readable failure retained across asynchronous phases; messages are never parsed for codes. */
public final class BridgeFailure extends IllegalArgumentException {
    private final String code;
    public BridgeFailure(String code, String message) {
        super(message);
        if (code == null || !code.matches("[A-Z_]{1,48}")) throw new IllegalArgumentException("Invalid failure code");
        this.code = code;
    }
    public String code() { return code; }
}
