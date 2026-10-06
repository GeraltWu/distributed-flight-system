package flight.server;

import flight.protocol.Message;
import flight.protocol.MessageCodec;
import flight.protocol.Protocol;
import flight.protocol.ProtocolException;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;

/** 接收 UDP 请求并调用航班服务。 */
final class FlightServer {
    private static final int RECEIVE_TIMEOUT_MILLIS = 200;

    private final int port;
    private final FlightRequestHandler requestHandler;
    private final InvocationMode mode;
    private final LossSimulator lossSimulator;
    private final ReplyHistory replyHistory = new ReplyHistory();

    FlightServer(
            int port,
            FlightService flightService,
            InvocationMode mode,
            LossSimulator lossSimulator) {
        this.port = port;
        this.requestHandler = new FlightRequestHandler(flightService);
        this.mode = mode;
        this.lossSimulator = lossSimulator;
    }

    void run() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.setSoTimeout(RECEIVE_TIMEOUT_MILLIS);
            System.out.println("[CONFIG] UDP port: " + port);
            System.out.println("[CONFIG] Invocation mode: " + mode);
            System.out.println("[CONFIG] Loss simulation: " + lossSimulator);

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
            System.out.println("[DROP] Invalid datagram: " + exception.getMessage());
            return;
        }

        if (message.messageType() == Protocol.MessageType.ACKNOWLEDGEMENT) {
            handleAcknowledgement(message);
            return;
        }
        if (message.messageType() != Protocol.MessageType.REQUEST) {
            System.out.println("[DROP] Non-request message, messageType="
                    + message.messageType());
            return;
        }

        System.out.printf(
                "[REQUEST] Received clientId=%s, requestId=%s, operation=%s(%d)%n",
                Long.toUnsignedString(message.clientId()),
                Long.toUnsignedString(message.requestId()),
                Protocol.operationName(message.operation()),
                message.operation());

        // 请求丢失发生在业务处理和 history 记录之前。
        if (lossSimulator.shouldDropRequest(message)) {
            System.out.printf(
                    "[LOSS] Simulated request loss: requestId=%s, operation=%s(%d)%n",
                    Long.toUnsignedString(message.requestId()),
                    Protocol.operationName(message.operation()),
                    message.operation());
            return;
        }

        try {
            // 格式错误的请求不进入 history，也不占用最高请求编号。
            requestHandler.validateRequestBody(message);
            if (mode == InvocationMode.AT_MOST_ONCE) {
                processAtMostOnce(socket, packet, message);
            } else {
                sendReply(socket, packet, requestHandler.executeRequest(message));
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
                Message reply = requestHandler.executeRequest(request);
                replyHistory.remember(request, reply, System.nanoTime());
                sendReply(socket, packet, reply);
                break;
            case REPLAY_CACHED:
                System.out.println("[HISTORY] Replaying cached reply for requestId="
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
            System.out.println("[ACK] Reply acknowledged for requestId="
                    + Long.toUnsignedString(acknowledgement.requestId()));
        } else {
            System.out.println("[ACK] Ignoring unmatched acknowledgement for requestId="
                    + Long.toUnsignedString(acknowledgement.requestId()));
        }
    }

    private void sendError(
            DatagramSocket socket,
            DatagramPacket requestPacket,
            Message request,
            int status,
            String description) {
        try {
            sendReply(
                    socket,
                    requestPacket,
                    requestHandler.errorReply(request, status, description));
        } catch (ProtocolException exception) {
            // 内置错误说明都很短；若这里失败，只记录服务端自身的编码问题。
            System.out.println("[ERROR] Failed to encode error reply: "
                    + exception.getMessage());
        }
    }

    private void sendReply(
            DatagramSocket socket, DatagramPacket requestPacket, Message reply) {
        // 回复已经生成，丢弃时不调用 socket.send；至多一次缓存仍然保留。
        if (lossSimulator.shouldDropReply(reply)) {
            System.out.printf(
                    "[LOSS] Simulated reply loss: requestId=%s, operation=%s(%d)%n",
                    Long.toUnsignedString(reply.requestId()),
                    Protocol.operationName(reply.operation()),
                    reply.operation());
            return;
        }

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
                    "[REPLY] Sent requestId=%s, operation=%s(%d), bytes=%d%n",
                    Long.toUnsignedString(reply.requestId()),
                    Protocol.operationName(reply.operation()),
                    reply.operation(),
                    encoded.length);
        } catch (IOException exception) {
            System.out.println("[ERROR] Failed to send reply: " + exception.getMessage());
        }
    }
}
