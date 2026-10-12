package flight.protocol;

/**
 * Indicates that a field value or byte sequence violates the protocol.
 * 表示字段值或字节序列不符合协议。
 */
public final class ProtocolException extends Exception {
    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
