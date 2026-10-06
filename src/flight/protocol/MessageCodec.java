package flight.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** UDP 协议的手写编解码器，只按偏移操作字节数组。 */
public final class MessageCodec {
    private MessageCodec() {
    }

    /** 把一条消息编码成一个 UDP 应用层载荷。 */
    public static byte[] encode(Message message) {
        Objects.requireNonNull(message, "message");
        byte[] body = message.body();

        // Message 构造时已经校验字段和消息体长度，这里只按固定偏移写入。
        byte[] datagram = new byte[Protocol.HEADER_LENGTH + body.length];
        datagram[Protocol.MAGIC_OFFSET] = (byte) Protocol.MAGIC_FIRST;
        datagram[Protocol.MAGIC_OFFSET + 1] = (byte) Protocol.MAGIC_SECOND;
        datagram[Protocol.VERSION_OFFSET] = (byte) Protocol.VERSION;
        datagram[Protocol.MESSAGE_TYPE_OFFSET] = (byte) message.messageType();
        datagram[Protocol.OPERATION_OFFSET] = (byte) message.operation();
        writeI64(datagram, Protocol.CLIENT_ID_OFFSET, message.clientId());
        writeI64(datagram, Protocol.REQUEST_ID_OFFSET, message.requestId());
        writeU16(datagram, Protocol.BODY_LENGTH_OFFSET, body.length);
        System.arraycopy(body, 0, datagram, Protocol.BODY_OFFSET, body.length);
        return datagram;
    }

    /** 解码恰好包含一个完整数据报的字节数组。 */
    public static Message decode(byte[] datagram) throws ProtocolException {
        Objects.requireNonNull(datagram, "datagram");
        return decode(datagram, 0, datagram.length);
    }

    /** 解码从 {@code offset} 开始的 {@code length} 个字节，供 DatagramPacket 直接调用。 */
    public static Message decode(byte[] datagram, int offset, int length)
            throws ProtocolException {
        Objects.requireNonNull(datagram, "datagram");
        checkSlice(datagram.length, offset, length);

        // 先检查外层长度，之后读取固定头字段时才不会越界。
        if (length < Protocol.HEADER_LENGTH) {
            throw new ProtocolException(
                    "Datagram is shorter than the " + Protocol.HEADER_LENGTH + "-byte header");
        }
        if (length > Protocol.MAX_DATAGRAM_LENGTH) {
            throw new ProtocolException(
                    "Datagram exceeds " + Protocol.MAX_DATAGRAM_LENGTH + " bytes: " + length);
        }

        // magic 和 version 不正确时，后面的字段布局不再可信，整包直接拒绝。
        if (readU8(datagram, offset + Protocol.MAGIC_OFFSET) != Protocol.MAGIC_FIRST
                || readU8(datagram, offset + Protocol.MAGIC_OFFSET + 1)
                        != Protocol.MAGIC_SECOND) {
            throw new ProtocolException("Invalid protocol magic");
        }

        int version = readU8(datagram, offset + Protocol.VERSION_OFFSET);
        if (version != Protocol.VERSION) {
            throw new ProtocolException("Unsupported protocol version: " + version);
        }

        int messageType = readU8(datagram, offset + Protocol.MESSAGE_TYPE_OFFSET);
        if (!Protocol.isKnownMessageType(messageType)) {
            throw new ProtocolException("Unknown message type: " + messageType);
        }

        int operation = readU8(datagram, offset + Protocol.OPERATION_OFFSET);
        // 未知 operation 在这里不报错，服务端业务层会返回 UNSUPPORTED。
        if (messageType == Protocol.MessageType.EVENT
                && operation != Protocol.Operation.MONITOR) {
            throw new ProtocolException("Event messages must use the MONITOR operation");
        }
        long clientId = readI64(datagram, offset + Protocol.CLIENT_ID_OFFSET);
        long requestId = readI64(datagram, offset + Protocol.REQUEST_ID_OFFSET);
        if (requestId == 0) {
            throw new ProtocolException("requestId must be non-zero");
        }

        int bodyLength = readU16(datagram, offset + Protocol.BODY_LENGTH_OFFSET);
        if (bodyLength > Protocol.MAX_BODY_LENGTH) {
            throw new ProtocolException("Body is too large: " + bodyLength);
        }
        // 必须恰好相等，同时拒绝被截断的消息体和消息体后的多余字节。
        if (length != Protocol.HEADER_LENGTH + bodyLength) {
            throw new ProtocolException(
                    "Datagram length does not match bodyLength: length="
                            + length
                            + ", bodyLength="
                            + bodyLength);
        }
        if (messageType == Protocol.MessageType.ACKNOWLEDGEMENT && bodyLength != 0) {
            throw new ProtocolException("Acknowledgement body must be empty");
        }

        int bodyStart = offset + Protocol.BODY_OFFSET;
        byte[] body = Arrays.copyOfRange(datagram, bodyStart, bodyStart + bodyLength);
        return new Message(messageType, operation, clientId, requestId, body);
    }

    /** 创建操作消息体的写入器。 */
    public static BodyWriter bodyWriter() {
        return new BodyWriter();
    }

    /** 创建操作消息体的读取器。 */
    public static BodyReader bodyReader(byte[] body) {
        return new BodyReader(body);
    }

    public static final class BodyWriter {
        private final byte[] buffer = new byte[Protocol.MAX_BODY_LENGTH];
        private int position;

        private BodyWriter() {
        }

        public BodyWriter writeU8(int value) throws ProtocolException {
            requireUnsigned(value, 0xffL, "u8");
            ensureWritable(1);
            buffer[position++] = (byte) value;
            return this;
        }

        public BodyWriter writeU16(int value) throws ProtocolException {
            requireUnsigned(value, 0xffffL, "u16");
            ensureWritable(2);
            MessageCodec.writeU16(buffer, position, value);
            position += 2;
            return this;
        }

        public BodyWriter writeU32(long value) throws ProtocolException {
            requireUnsigned(value, 0xffff_ffffL, "u32");
            ensureWritable(4);
            MessageCodec.writeI32(buffer, position, (int) value);
            position += 4;
            return this;
        }

        public BodyWriter writeI32(int value) throws ProtocolException {
            ensureWritable(4);
            MessageCodec.writeI32(buffer, position, value);
            position += 4;
            return this;
        }

        public BodyWriter writeI64(long value) throws ProtocolException {
            ensureWritable(8);
            MessageCodec.writeI64(buffer, position, value);
            position += 8;
            return this;
        }

        public BodyWriter writeFloat32(float value) throws ProtocolException {
            // 不做数值转换，直接写入 IEEE 754 的 32 位位模式。
            return writeI32(Float.floatToRawIntBits(value));
        }

        public BodyWriter writeString(String value) throws ProtocolException {
            Objects.requireNonNull(value, "value");
            byte[] encoded = encodeUtf8(value);

            // 长度写的是 UTF-8 字节数，不是 Java 字符数。
            // 消息体最多 525 字节，所以一定也能放入 u16。
            ensureWritable(2 + encoded.length);
            MessageCodec.writeU16(buffer, position, encoded.length);
            position += 2;
            System.arraycopy(encoded, 0, buffer, position, encoded.length);
            position += encoded.length;
            return this;
        }

        public byte[] toByteArray() {
            return Arrays.copyOf(buffer, position);
        }

        private void ensureWritable(int count) throws ProtocolException {
            if (count < 0 || count > buffer.length - position) {
                throw new ProtocolException(
                        "Message body exceeds " + Protocol.MAX_BODY_LENGTH + " bytes");
            }
        }
    }

    public static final class BodyReader {
        private final byte[] buffer;
        private int position;

        private BodyReader(byte[] body) {
            Objects.requireNonNull(body, "body");
            if (body.length > Protocol.MAX_BODY_LENGTH) {
                throw new IllegalArgumentException(
                        "Body exceeds " + Protocol.MAX_BODY_LENGTH + " bytes");
            }
            // 读取器只在当前调用中顺序读取，不再额外复制这段小消息体。
            this.buffer = body;
        }

        public int readU8() throws ProtocolException {
            ensureReadable(1);
            return MessageCodec.readU8(buffer, position++);
        }

        public int readU16() throws ProtocolException {
            ensureReadable(2);
            int value = MessageCodec.readU16(buffer, position);
            position += 2;
            return value;
        }

        public long readU32() throws ProtocolException {
            ensureReadable(4);
            long value = Integer.toUnsignedLong(MessageCodec.readI32(buffer, position));
            position += 4;
            return value;
        }

        public int readI32() throws ProtocolException {
            ensureReadable(4);
            int value = MessageCodec.readI32(buffer, position);
            position += 4;
            return value;
        }

        public long readI64() throws ProtocolException {
            ensureReadable(8);
            long value = MessageCodec.readI64(buffer, position);
            position += 8;
            return value;
        }

        public float readFloat32() throws ProtocolException {
            return Float.intBitsToFloat(readI32());
        }

        public String readString() throws ProtocolException {
            // 先读 u16 字节长度，再只解码这一段；非法 UTF-8 会抛出异常。
            int byteLength = readU16();
            ensureReadable(byteLength);
            String decoded = decodeUtf8(buffer, position, byteLength);
            position += byteLength;
            return decoded;
        }

        public int remaining() {
            return buffer.length - position;
        }

        public void requireFullyRead() throws ProtocolException {
            // 每种 operation 都有固定字段顺序，多出的尾部字节同样属于格式错误。
            if (position != buffer.length) {
                throw new ProtocolException(
                        "Message body contains " + remaining() + " unexpected trailing bytes");
            }
        }

        private void ensureReadable(int count) throws ProtocolException {
            if (count < 0 || count > buffer.length - position) {
                throw new ProtocolException(
                        "Message body ended early at offset "
                                + position
                                + "; needed "
                                + count
                                + " bytes but only "
                                + remaining()
                                + " remain");
            }
        }
    }

    private static void checkSlice(int arrayLength, int offset, int length)
            throws ProtocolException {
        if (offset < 0 || length < 0 || offset > arrayLength - length) {
            throw new ProtocolException(
                    "Invalid datagram slice: offset=" + offset + ", length=" + length);
        }
    }

    private static void requireUnsigned(long value, long maximum, String type)
            throws ProtocolException {
        if (value < 0 || value > maximum) {
            throw new ProtocolException(type + " value out of range: " + value);
        }
    }

    private static byte[] encodeUtf8(String value) {
        // Java 字符串直接编码即可；严格 UTF-8 检查只用于不可信的网络输入。
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length)
            throws ProtocolException {
        try {
            // 默认解码器会用替代字符掩盖错误，这里要求直接拒绝非法 UTF-8。
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new ProtocolException("String contains invalid UTF-8", exception);
        }
    }

    // 以下方法用移位显式实现网络字节序，不依赖 DataInputStream/DataOutputStream。
    private static int readU8(byte[] bytes, int offset) {
        return bytes[offset] & 0xff;
    }

    private static int readU16(byte[] bytes, int offset) {
        return (readU8(bytes, offset) << 8) | readU8(bytes, offset + 1);
    }

    private static int readI32(byte[] bytes, int offset) {
        return (readU8(bytes, offset) << 24)
                | (readU8(bytes, offset + 1) << 16)
                | (readU8(bytes, offset + 2) << 8)
                | readU8(bytes, offset + 3);
    }

    private static long readI64(byte[] bytes, int offset) {
        return ((long) readU8(bytes, offset) << 56)
                | ((long) readU8(bytes, offset + 1) << 48)
                | ((long) readU8(bytes, offset + 2) << 40)
                | ((long) readU8(bytes, offset + 3) << 32)
                | ((long) readU8(bytes, offset + 4) << 24)
                | ((long) readU8(bytes, offset + 5) << 16)
                | ((long) readU8(bytes, offset + 6) << 8)
                | (long) readU8(bytes, offset + 7);
    }

    private static void writeU16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }

    private static void writeI32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static void writeI64(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) (value >>> 56);
        bytes[offset + 1] = (byte) (value >>> 48);
        bytes[offset + 2] = (byte) (value >>> 40);
        bytes[offset + 3] = (byte) (value >>> 32);
        bytes[offset + 4] = (byte) (value >>> 24);
        bytes[offset + 5] = (byte) (value >>> 16);
        bytes[offset + 6] = (byte) (value >>> 8);
        bytes[offset + 7] = (byte) value;
    }
}
