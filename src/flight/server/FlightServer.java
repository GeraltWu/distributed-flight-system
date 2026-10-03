package flight.server;

import flight.protocol.Message;
import flight.protocol.MessageCodec;
import flight.protocol.Protocol;
import flight.protocol.ProtocolException;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

/** 接收 UDP 请求并调用航班服务。当前只处理 DETAILS。 */
final class FlightServer {
    private final int port;
    private final FlightService flightService;

    FlightServer(int port, FlightService flightService) {
        this.port = port;
        this.flightService = flightService;
    }

    void run() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            System.out.println("服务端已启动，UDP 端口：" + port);

            while (true) {
                byte[] buffer = new byte[Protocol.MAX_DATAGRAM_LENGTH];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                processPacket(socket, packet);
            }
        }
    }

    private void processPacket(DatagramSocket socket, DatagramPacket packet) {
        Message request;
        try {
            // DatagramPacket 的缓冲区可能比实际数据长，只解码收到的有效区间。
            request = MessageCodec.decode(packet.getData(), packet.getOffset(), packet.getLength());
        } catch (ProtocolException exception) {
            // 头部不可信时无法安全复制 clientId/requestId，因此只记录并丢弃。
            System.out.println("丢弃非法报文：" + exception.getMessage());
            return;
        }

        if (request.messageType() != Protocol.MessageType.REQUEST) {
            System.out.println("丢弃非请求报文，messageType=" + request.messageType());
            return;
        }

        System.out.printf(
                "收到请求：clientId=%s, requestId=%s, operation=%d%n",
                Long.toUnsignedString(request.clientId()),
                Long.toUnsignedString(request.requestId()),
                request.operation());

        try {
            if (request.operation() == Protocol.Operation.DETAILS) {
                handleDetails(socket, packet, request);
            } else {
                sendError(
                        socket,
                        packet,
                        request,
                        Protocol.Status.UNSUPPORTED,
                        "当前服务端尚未实现该操作");
            }
        } catch (ProtocolException exception) {
            // 消息头已经通过检查，此时可以复制请求编号返回 BAD_PACKET。
            sendError(
                    socket,
                    packet,
                    request,
                    Protocol.Status.BAD_PACKET,
                    "请求消息体格式错误");
        }
    }

    private void handleDetails(DatagramSocket socket, DatagramPacket packet, Message request)
            throws ProtocolException {
        MessageCodec.BodyReader reader = MessageCodec.bodyReader(request.body());
        int flightId = reader.readI32();
        reader.requireFullyRead();

        if (flightId <= 0) {
            sendError(socket, packet, request, Protocol.Status.BAD_ARGUMENT, "航班号必须为正整数");
            return;
        }

        Flight flight = flightService.findById(flightId);
        if (flight == null) {
            sendError(socket, packet, request, Protocol.Status.NOT_FOUND, "航班不存在");
            return;
        }

        byte[] body = MessageCodec.bodyWriter()
                .writeU8(Protocol.Status.OK)
                .writeI64(flight.departureUtcSeconds())
                .writeFloat32(flight.fare())
                .writeI32(flight.availableSeats())
                .toByteArray();
        sendReply(socket, packet, Message.replyTo(request, body));
    }

    private void sendError(
            DatagramSocket socket,
            DatagramPacket requestPacket,
            Message request,
            int status,
            String description) {
        try {
            byte[] body = MessageCodec.bodyWriter()
                    .writeU8(status)
                    .writeString(description)
                    .toByteArray();
            sendReply(socket, requestPacket, Message.replyTo(request, body));
        } catch (ProtocolException exception) {
            // 内置错误说明都很短；若这里失败，只记录服务端自身的编码问题。
            System.out.println("无法编码错误回复：" + exception.getMessage());
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
                    "发送回复：requestId=%s, operation=%d, bytes=%d%n",
                    Long.toUnsignedString(reply.requestId()),
                    reply.operation(),
                    encoded.length);
        } catch (IOException exception) {
            System.out.println("发送回复失败：" + exception.getMessage());
        }
    }
}
