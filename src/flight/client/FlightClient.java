package flight.client;

import flight.protocol.Message;
import flight.protocol.MessageCodec;
import flight.protocol.Protocol;
import flight.protocol.ProtocolException;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.ThreadLocalRandom;

/** 负责客户端的 UDP 收发。当前只提供 DETAILS 查询。 */
final class FlightClient implements AutoCloseable {
    private static final int RECEIVE_TIMEOUT_MILLIS = 3_000;

    private final InetAddress serverAddress;
    private final int serverPort;
    private final DatagramSocket socket;
    private final long clientId;
    private long nextRequestId = 1;

    FlightClient(InetAddress serverAddress, int serverPort) throws IOException {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(RECEIVE_TIMEOUT_MILLIS);

        this.clientId = ThreadLocalRandom.current().nextLong();
    }

    FlightDetails queryDetails(int flightId) throws IOException, ProtocolException {
        long requestId = nextRequestId++;
        byte[] body = MessageCodec.bodyWriter().writeI32(flightId).toByteArray();
        Message request = Message.request(
                Protocol.Operation.DETAILS, clientId, requestId, body);

        byte[] encoded = MessageCodec.encode(request);
        DatagramPacket requestPacket = new DatagramPacket(
                encoded, encoded.length, serverAddress, serverPort);
        socket.send(requestPacket);
        System.out.println("请求已发送，等待服务端回复……");

        Message reply = receiveMatchingReply(request);
        MessageCodec.BodyReader reader = MessageCodec.bodyReader(reply.body());
        int status = reader.readU8();

        if (status == Protocol.Status.OK) {
            FlightDetails details = new FlightDetails(
                    reader.readI64(), reader.readFloat32(), reader.readI32());
            reader.requireFullyRead();
            return details;
        }

        // 错误回复只能包含 status、u16 长度和最多 512 字节的说明。
        if (status > Protocol.Status.STALE_REQUEST
                || reply.bodyLength() > 1 + 2 + Protocol.MAX_ERROR_TEXT_LENGTH) {
            throw new ProtocolException("Invalid error reply");
        }
        String description = reader.readString();
        reader.requireFullyRead();
        throw new IOException("服务端返回错误（status=" + status + "）：" + description);
    }

    private Message receiveMatchingReply(Message request)
            throws IOException, ProtocolException {
        while (true) {
            byte[] buffer = new byte[Protocol.MAX_DATAGRAM_LENGTH];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            try {
                socket.receive(packet);
            } catch (SocketTimeoutException exception) {
                throw new IOException("等待服务端回复超时", exception);
            }

            // 只接受配置的服务端发来的数据报，避免其他 UDP 数据干扰当前请求。
            if (!packet.getAddress().equals(serverAddress) || packet.getPort() != serverPort) {
                continue;
            }

            Message reply;
            try {
                reply = MessageCodec.decode(packet.getData(), packet.getOffset(), packet.getLength());
            } catch (ProtocolException exception) {
                System.out.println("忽略非法回复：" + exception.getMessage());
                continue;
            }

            // 四个字段全部匹配，才能确定这是当前请求的回复。
            if (reply.messageType() == Protocol.MessageType.REPLY
                    && reply.operation() == request.operation()
                    && reply.clientId() == request.clientId()
                    && reply.requestId() == request.requestId()) {
                return reply;
            }
        }
    }

    @Override
    public void close() {
        socket.close();
    }

    /** 客户端解码后的航班详情。 */
    static final class FlightDetails {
        private final long departureUtcSeconds;
        private final float fare;
        private final int availableSeats;

        FlightDetails(long departureUtcSeconds, float fare, int availableSeats) {
            this.departureUtcSeconds = departureUtcSeconds;
            this.fare = fare;
            this.availableSeats = availableSeats;
        }

        long departureUtcSeconds() {
            return departureUtcSeconds;
        }

        float fare() {
            return fare;
        }

        int availableSeats() {
            return availableSeats;
        }
    }
}
