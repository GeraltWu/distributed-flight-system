package flight.server;

import flight.protocol.Message;
import flight.protocol.MessageCodec;
import flight.protocol.Protocol;
import flight.protocol.ProtocolException;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;

/** 接收 UDP 请求并调用航班服务。当前只处理 DETAILS。 */
final class FlightServer {
    private static final int RECEIVE_TIMEOUT_MILLIS = 200;

    private final int port;
    private final FlightService flightService;
    private final InvocationMode mode;
    private final ReplyHistory replyHistory = new ReplyHistory();

    FlightServer(int port, FlightService flightService, InvocationMode mode) {
        this.port = port;
        this.flightService = flightService;
        this.mode = mode;
    }

    void run() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.setSoTimeout(RECEIVE_TIMEOUT_MILLIS);
            System.out.println("Server started on UDP port: " + port);
            System.out.println("Invocation mode: " + mode);

            while (true) {
                byte[] buffer = new byte[Protocol.MAX_DATAGRAM_LENGTH];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    processPacket(socket, packet);
                } catch (SocketTimeoutException exception) {
                    // 定期醒来清理历史；接收超时本身不是错误。
                }

                if (mode == InvocationMode.AT_MOST_ONCE) {
                    replyHistory.removeExpiredReplies(System.nanoTime());
                }
            }
        }
    }

    private void processPacket(DatagramSocket socket, DatagramPacket packet) {
        Message message;
        try {
            // DatagramPacket 的缓冲区可能比实际数据长，只解码收到的有效区间。
            message = MessageCodec.decode(packet.getData(), packet.getOffset(), packet.getLength());
        } catch (ProtocolException exception) {
            // 头部不可信时无法安全复制 clientId/requestId，因此只记录并丢弃。
            System.out.println("Dropping invalid datagram: " + exception.getMessage());
            return;
        }

        if (message.messageType() == Protocol.MessageType.ACKNOWLEDGEMENT) {
            handleAcknowledgement(message);
            return;
        }
        if (message.messageType() != Protocol.MessageType.REQUEST) {
            System.out.println("Dropping non-request message, messageType="
                    + message.messageType());
            return;
        }

        System.out.printf(
                "Received request: clientId=%s, requestId=%s, operation=%d%n",
                Long.toUnsignedString(message.clientId()),
                Long.toUnsignedString(message.requestId()),
                message.operation());

        try {
            // 格式错误的请求不进入 history，也不占用最高请求编号。
            validateRequestBody(message);
            if (mode == InvocationMode.AT_MOST_ONCE) {
                processAtMostOnce(socket, packet, message);
            } else {
                sendReply(socket, packet, executeRequest(message));
            }
        } catch (ProtocolException exception) {
            sendError(
                    socket,
                    packet,
                    message,
                    Protocol.Status.BAD_PACKET,
                    "Malformed request body");
        }
    }

    private void processAtMostOnce(
            DatagramSocket socket, DatagramPacket packet, Message request)
            throws ProtocolException {
        long nowNanos = System.nanoTime();
        ReplyHistory.Decision decision = replyHistory.classify(request, nowNanos);

        switch (decision) {
            case NEW_REQUEST:
                // 先保存回复再发送；即使回复丢失，重传也不会再次执行业务操作。
                Message reply = executeRequest(request);
                replyHistory.remember(request, reply, System.nanoTime());
                sendReply(socket, packet, reply);
                break;
            case REPLAY_CACHED:
                System.out.println("Replaying cached reply for requestId="
                        + Long.toUnsignedString(request.requestId()));
                sendReply(socket, packet, replyHistory.cachedReply(request.clientId()));
                break;
            case CONFLICT:
                sendError(
                        socket,
                        packet,
                        request,
                        Protocol.Status.BAD_ARGUMENT,
                        "Request ID was reused with different content");
                break;
            case STALE:
                sendError(
                        socket,
                        packet,
                        request,
                        Protocol.Status.STALE_REQUEST,
                        "Request ID is stale");
                break;
            default:
                throw new IllegalStateException("Unknown history decision: " + decision);
        }
    }

    private void handleAcknowledgement(Message acknowledgement) {
        if (mode != InvocationMode.AT_MOST_ONCE) {
            return;
        }

        if (replyHistory.acknowledge(acknowledgement)) {
            System.out.println("Reply acknowledged for requestId="
                    + Long.toUnsignedString(acknowledgement.requestId()));
        } else {
            System.out.println("Ignoring unmatched acknowledgement for requestId="
                    + Long.toUnsignedString(acknowledgement.requestId()));
        }
    }

    private void validateRequestBody(Message request) throws ProtocolException {
        MessageCodec.BodyReader reader = MessageCodec.bodyReader(request.body());
        switch (request.operation()) {
            case Protocol.Operation.ROUTE:
                reader.readString();
                reader.readString();
                reader.readU32();
                break;
            case Protocol.Operation.DETAILS:
                reader.readI32();
                break;
            case Protocol.Operation.RESERVE:
            case Protocol.Operation.ADD_SEATS:
                reader.readI32();
                reader.readI32();
                break;
            case Protocol.Operation.MONITOR:
                reader.readI32();
                reader.readU32();
                break;
            case Protocol.Operation.SET_FARE:
                reader.readI32();
                reader.readFloat32();
                break;
            default:
                // 未知操作没有可验证的消息体格式，由业务层返回 UNSUPPORTED。
                return;
        }
        reader.requireFullyRead();
    }

    private Message executeRequest(Message request) throws ProtocolException {
        if (request.operation() == Protocol.Operation.DETAILS) {
            return handleDetails(request);
        }
        return errorReply(request, Protocol.Status.UNSUPPORTED, "Operation is not implemented");
    }

    private Message handleDetails(Message request) throws ProtocolException {
        MessageCodec.BodyReader reader = MessageCodec.bodyReader(request.body());
        int flightId = reader.readI32();
        reader.requireFullyRead();

        if (flightId <= 0) {
            return errorReply(
                    request,
                    Protocol.Status.BAD_ARGUMENT,
                    "Flight ID must be a positive integer");
        }

        Flight flight = flightService.findById(flightId);
        if (flight == null) {
            return errorReply(request, Protocol.Status.NOT_FOUND, "Flight not found");
        }

        byte[] body = MessageCodec.bodyWriter()
                .writeU8(Protocol.Status.OK)
                .writeI64(flight.departureUtcSeconds())
                .writeFloat32(flight.fare())
                .writeI32(flight.availableSeats())
                .toByteArray();
        return Message.replyTo(request, body);
    }

    private Message errorReply(Message request, int status, String description)
            throws ProtocolException {
        byte[] body = MessageCodec.bodyWriter()
                .writeU8(status)
                .writeString(description)
                .toByteArray();
        return Message.replyTo(request, body);
    }

    private void sendError(
            DatagramSocket socket,
            DatagramPacket requestPacket,
            Message request,
            int status,
            String description) {
        try {
            sendReply(socket, requestPacket, errorReply(request, status, description));
        } catch (ProtocolException exception) {
            // 内置错误说明都很短；若这里失败，只记录服务端自身的编码问题。
            System.out.println("Failed to encode error reply: " + exception.getMessage());
        }
    }

    private void sendReply(
            DatagramSocket socket, DatagramPacket requestPacket, Message reply) {
        byte[] encoded = MessageCodec.encode(reply);
        DatagramPacket replyPacket = new DatagramPacket(
                encoded,
                encoded.length,
                requestPacket.getAddress(),
                requestPacket.getPort());

        try {
            // 回复必须发回请求数据报的真实来源地址和端口。
            socket.send(replyPacket);
            System.out.printf(
                    "Sent reply: requestId=%s, operation=%d, bytes=%d%n",
                    Long.toUnsignedString(reply.requestId()),
                    reply.operation(),
                    encoded.length);
        } catch (IOException exception) {
            System.out.println("Failed to send reply: " + exception.getMessage());
        }
    }
}
