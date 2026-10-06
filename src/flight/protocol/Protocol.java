package flight.protocol;

/** protocol.md 定义的报文字段、偏移和取值常量。 */
public final class Protocol {
    private Protocol() {
    }

    public static final int MAGIC_FIRST = 0x46;  // 字符 F
    public static final int MAGIC_SECOND = 0x49; // 字符 I
    public static final int VERSION = 1;

    // 数据报大小由 protocol.md 固定，消息体上限由总长度减去消息头得到。
    public static final int HEADER_LENGTH = 23;
    public static final int MAX_DATAGRAM_LENGTH = 548;
    public static final int MAX_BODY_LENGTH = MAX_DATAGRAM_LENGTH - HEADER_LENGTH;
    public static final int MAX_ERROR_TEXT_LENGTH = 512;
    public static final int MAX_ROUTE_RESULTS_PER_PAGE = 100;

    // 所有偏移都从 UDP 应用层载荷的第一个字节开始计算。
    public static final int MAGIC_OFFSET = 0;
    public static final int VERSION_OFFSET = 2;
    public static final int MESSAGE_TYPE_OFFSET = 3;
    public static final int OPERATION_OFFSET = 4;
    public static final int CLIENT_ID_OFFSET = 5;
    public static final int REQUEST_ID_OFFSET = 13;
    public static final int BODY_LENGTH_OFFSET = 21;
    public static final int BODY_OFFSET = HEADER_LENGTH;

    public static final class MessageType {
        private MessageType() {
        }

        public static final int REQUEST = 1;
        public static final int REPLY = 2;
        public static final int EVENT = 3;
        public static final int ACKNOWLEDGEMENT = 4;
    }

    public static final class Operation {
        private Operation() {
        }

        public static final int ROUTE = 1;
        public static final int DETAILS = 2;
        public static final int RESERVE = 3;
        public static final int MONITOR = 4;
        public static final int SET_FARE = 5;
        public static final int ADD_SEATS = 6;
    }

    public static final class Status {
        private Status() {
        }

        public static final int OK = 0;
        public static final int BAD_ARGUMENT = 1;
        public static final int NOT_FOUND = 2;
        public static final int NO_SEATS = 3;
        public static final int BAD_PACKET = 4;
        public static final int UNSUPPORTED = 5;
        public static final int STALE_REQUEST = 6;
    }

    public static boolean isKnownMessageType(int messageType) {
        return messageType == MessageType.REQUEST
                || messageType == MessageType.REPLY
                || messageType == MessageType.EVENT
                || messageType == MessageType.ACKNOWLEDGEMENT;
    }

    public static String operationName(int operation) {
        switch (operation) {
            case Operation.ROUTE:
                return "ROUTE";
            case Operation.DETAILS:
                return "DETAILS";
            case Operation.RESERVE:
                return "RESERVE";
            case Operation.MONITOR:
                return "MONITOR";
            case Operation.SET_FARE:
                return "SET_FARE";
            case Operation.ADD_SEATS:
                return "ADD_SEATS";
            default:
                return "UNKNOWN";
        }
    }
}
