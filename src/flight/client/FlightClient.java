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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** 负责客户端的 UDP 请求、重传和回复解码。 */
final class FlightClient implements AutoCloseable {
    private static final int RECEIVE_TIMEOUT_MILLIS = 800;
    private static final int MAX_SEND_ATTEMPTS = 4;

    private final InetAddress serverAddress;
    private final int serverPort;
    private final DatagramSocket socket;
    private final long clientId;
    private long nextRequestId = 1;

    FlightClient(InetAddress serverAddress, int serverPort) throws IOException {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.socket = new DatagramSocket();
        this.clientId = ThreadLocalRandom.current().nextLong();
    }

    List<Integer> queryRoute(String source, String destination)
            throws IOException, ProtocolException {
        List<Integer> flightIds = new ArrayList<>();
        long offset = 0;

        while (true) {
            byte[] body = MessageCodec.bodyWriter()
                    .writeString(source)
                    .writeString(destination)
                    .writeU32(offset)
                    .toByteArray();
            Message reply = invoke(Protocol.Operation.ROUTE, body);
            MessageCodec.BodyReader reader = successReader(reply);

            int hasMore = reader.readU8();
            int count = reader.readU16();
            if ((hasMore != 0 && hasMore != 1)
                    || count > Protocol.MAX_ROUTE_RESULTS_PER_PAGE
                    || count == 0) {
                throw new ProtocolException("Invalid route page");
            }

            for (int index = 0; index < count; index++) {
                int flightId = reader.readI32();
                if (flightId <= 0
                        || (!flightIds.isEmpty()
                                && flightId <= flightIds.get(flightIds.size() - 1))) {
                    throw new ProtocolException("Route results are not in ascending order");
                }
                flightIds.add(flightId);
            }
            completeReply(reply, reader);

            if (hasMore == 0) {
                return flightIds;
            }
            offset += count;
        }
    }

    FlightDetails queryDetails(int flightId) throws IOException, ProtocolException {
        byte[] body = MessageCodec.bodyWriter().writeI32(flightId).toByteArray();
        Message reply = invoke(Protocol.Operation.DETAILS, body);
        MessageCodec.BodyReader reader = successReader(reply);
        long departure = reader.readI64();
        float fare = reader.readFloat32();
        int availableSeats = reader.readI32();
        if (!Float.isFinite(fare) || fare < 0 || availableSeats < 0) {
            throw new ProtocolException("Invalid flight details reply");
        }
        FlightDetails details = new FlightDetails(departure, fare, availableSeats);
        completeReply(reply, reader);
        return details;
    }

    int reserveSeats(int flightId, int seatCount) throws IOException, ProtocolException {
        byte[] body = MessageCodec.bodyWriter()
                .writeI32(flightId)
                .writeI32(seatCount)
                .toByteArray();
        return readSeatResult(invoke(Protocol.Operation.RESERVE, body));
    }

    float setFare(int flightId, float newFare) throws IOException, ProtocolException {
        byte[] body = MessageCodec.bodyWriter()
                .writeI32(flightId)
                .writeFloat32(newFare)
                .toByteArray();
        Message reply = invoke(Protocol.Operation.SET_FARE, body);
        MessageCodec.BodyReader reader = successReader(reply);
        float currentFare = reader.readFloat32();
        if (!Float.isFinite(currentFare) || currentFare < 0) {
            throw new ProtocolException("Invalid fare reply");
        }
        completeReply(reply, reader);
        return currentFare;
    }

    int addSeats(int flightId, int seatCount) throws IOException, ProtocolException {
        byte[] body = MessageCodec.bodyWriter()
                .writeI32(flightId)
                .writeI32(seatCount)
                .toByteArray();
        return readSeatResult(invoke(Protocol.Operation.ADD_SEATS, body));
    }

    private int readSeatResult(Message reply) throws IOException, ProtocolException {
        MessageCodec.BodyReader reader = successReader(reply);
        int availableSeats = reader.readI32();
        if (availableSeats < 0) {
            throw new ProtocolException("Invalid seat availability reply");
        }
        completeReply(reply, reader);
        return availableSeats;
    }

    private Message invoke(int operation, byte[] body) throws IOException {
        Message request = Message.request(operation, clientId, nextRequestId++, body);
        return sendWithRetry(request);
    }

    private MessageCodec.BodyReader successReader(Message reply)
            throws IOException, ProtocolException {
        MessageCodec.BodyReader reader = MessageCodec.bodyReader(reply.body());
        int status = reader.readU8();
        if (status == Protocol.Status.OK) {
            return reader;
        }

        // 错误回复只能包含 status、u16 长度和最多 512 字节的说明。
        if (status > Protocol.Status.STALE_REQUEST
                || reply.bodyLength() > 1 + 2 + Protocol.MAX_ERROR_TEXT_LENGTH) {
            throw new ProtocolException("Invalid error reply");
        }
        String description = reader.readString();
        reader.requireFullyRead();
        sendAcknowledgement(reply);
        throw new IOException("Server returned an error (status=" + status + "): " + description);
    }

    private void completeReply(Message reply, MessageCodec.BodyReader reader)
            throws ProtocolException {
        reader.requireFullyRead();
        sendAcknowledgement(reply);
    }

    private Message sendWithRetry(Message request) throws IOException {
        byte[] encoded = MessageCodec.encode(request);
        DatagramPacket requestPacket = new DatagramPacket(
                encoded, encoded.length, serverAddress, serverPort);

        for (int attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
            // 每次重发沿用完全相同的请求字节，包括 clientId 和 requestId。
            socket.send(requestPacket);
            System.out.printf(
                    "[REQUEST] Sent attempt %d/%d; waiting for reply...%n",
                    attempt,
                    MAX_SEND_ATTEMPTS);

            Message reply = receiveMatchingReply(request);
            if (reply != null) {
                return reply;
            }
        }

        throw new IOException("Timed out after " + MAX_SEND_ATTEMPTS + " attempts");
    }

    private Message receiveMatchingReply(Message request) throws IOException {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(RECEIVE_TIMEOUT_MILLIS);

        while (true) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return null;
            }

            // 非匹配数据报不会重新开始 800 ms 计时。
            int remainingMillis = (int) Math.max(
                    1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            socket.setSoTimeout(remainingMillis);

            byte[] buffer = new byte[Protocol.MAX_DATAGRAM_LENGTH];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException exception) {
                return null;
            }

            // 只接受配置的服务端发来的数据报，避免其他 UDP 数据干扰当前请求。
            if (!packet.getAddress().equals(serverAddress) || packet.getPort() != serverPort) {
                continue;
            }

            Message reply;
            try {
                reply = MessageCodec.decode(packet.getData(), packet.getOffset(), packet.getLength());
            } catch (ProtocolException exception) {
                System.out.println("[DROP] Invalid reply: " + exception.getMessage());
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

    private void sendAcknowledgement(Message reply) {
        byte[] encoded = MessageCodec.encode(Message.acknowledgementTo(reply));
        DatagramPacket packet = new DatagramPacket(
                encoded, encoded.length, serverAddress, serverPort);
        try {
            // 确认只帮助服务端提前清理缓存；发送失败时仍有 60 秒 TTL 兜底。
            socket.send(packet);
            System.out.println("[ACK] Reply acknowledgement sent.");
        } catch (IOException exception) {
            System.out.println("[ERROR] Failed to send reply acknowledgement: "
                    + exception.getMessage());
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
