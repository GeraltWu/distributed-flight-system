package flight.protocol;

import java.util.Arrays;
import java.util.Objects;

/** 一条完整且不可变的协议消息。 */
public final class Message {
    private final int messageType;
    private final int operation;
    private final long clientId;
    private final long requestId;
    private final byte[] body;

    Message(int messageType, int operation, long clientId, long requestId, byte[] body) {
        if (!Protocol.isKnownMessageType(messageType)) {
            throw new IllegalArgumentException("Unknown message type: " + messageType);
        }

        // 未知操作也要保留下来，服务端之后需要据此返回 UNSUPPORTED。
        if (operation < 0 || operation > 0xff) {
            throw new IllegalArgumentException("Operation must fit in u8: " + operation);
        }

        // 当前只有 MONITOR 会产生事件，其他组合属于非法消息头。
        if (messageType == Protocol.MessageType.EVENT
                && operation != Protocol.Operation.MONITOR) {
            throw new IllegalArgumentException("Event messages must use the MONITOR operation");
        }

        // 请求编号从 1 开始，0 不表示任何合法请求。
        if (requestId == 0) {
            throw new IllegalArgumentException("requestId must be a non-zero u64 value");
        }

        byte[] checkedBody = Objects.requireNonNull(body, "body");
        if (checkedBody.length > Protocol.MAX_BODY_LENGTH) {
            throw new IllegalArgumentException(
                    "Body exceeds " + Protocol.MAX_BODY_LENGTH + " bytes: " + checkedBody.length);
        }

        this.messageType = messageType;
        this.operation = operation;
        this.clientId = clientId;
        this.requestId = requestId;

        // Message 是不可变对象，不能继续引用调用者可能修改的数组。
        this.body = Arrays.copyOf(checkedBody, checkedBody.length);
    }

    public static Message request(int operation, long clientId, long requestId, byte[] body) {
        return new Message(Protocol.MessageType.REQUEST, operation, clientId, requestId, body);
    }

    /** 创建回复，并从请求复制用于匹配的三个字段。 */
    public static Message replyTo(Message request, byte[] body) {
        Objects.requireNonNull(request, "request");
        if (request.messageType != Protocol.MessageType.REQUEST) {
            throw new IllegalArgumentException("A reply can only be created from a request");
        }
        return new Message(
                Protocol.MessageType.REPLY,
                request.operation,
                request.clientId,
                request.requestId,
                body);
    }

    /** 确认已收到一条回复，三个匹配字段均从回复复制。 */
    public static Message acknowledgementTo(Message reply) {
        Objects.requireNonNull(reply, "reply");
        if (reply.messageType != Protocol.MessageType.REPLY) {
            throw new IllegalArgumentException("An acknowledgement requires a reply");
        }
        return new Message(
                Protocol.MessageType.ACKNOWLEDGEMENT,
                reply.operation,
                reply.clientId,
                reply.requestId,
                new byte[0]);
    }

    /** 为已有的 MONITOR 登记创建座位变化事件。 */
    public static Message monitorEvent(long clientId, long registrationRequestId, byte[] body) {
        return new Message(
                Protocol.MessageType.EVENT,
                Protocol.Operation.MONITOR,
                clientId,
                registrationRequestId,
                body);
    }

    public int messageType() {
        return messageType;
    }

    public int operation() {
        return operation;
    }

    /** Java 没有 u64，此处返回 clientId 的原始 64 位位模式。 */
    public long clientId() {
        return clientId;
    }

    /** Java 没有 u64，此处返回非零 requestId 的原始 64 位位模式。 */
    public long requestId() {
        return requestId;
    }

    public int bodyLength() {
        return body.length;
    }

    /** 返回副本，避免调用者修改已经构造好的消息。 */
    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }
}
