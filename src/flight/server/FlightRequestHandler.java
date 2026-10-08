package flight.server;

import flight.protocol.Message;
import flight.protocol.MessageCodec;
import flight.protocol.Protocol;
import flight.protocol.ProtocolException;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.net.InetSocketAddress;
import java.util.List;

/** 解码业务参数、执行航班操作并构造协议回复。 */
final class FlightRequestHandler {
    private final FlightService flightService;
    private final MonitorRegistry monitorRegistry;

    FlightRequestHandler(FlightService flightService, MonitorRegistry monitorRegistry) {
        this.flightService = flightService;
        this.monitorRegistry = monitorRegistry;
    }

    /** 只检查消息体结构；业务取值错误由具体操作返回 BAD_ARGUMENT。 */
    void validateRequestBody(Message request) throws ProtocolException {
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
                // 未知操作没有可验证的消息体格式，执行阶段返回 UNSUPPORTED。
                return;
        }
        reader.requireFullyRead();
    }

    Message executeRequest(
            Message request,
            InetSocketAddress clientEndpoint,
            long nowNanos) throws ProtocolException {
        switch (request.operation()) {
            case Protocol.Operation.ROUTE:
                return handleRoute(request);
            case Protocol.Operation.DETAILS:
                return handleDetails(request);
            case Protocol.Operation.RESERVE:
                return handleReserve(request);
            case Protocol.Operation.MONITOR:
                return handleMonitor(request, clientEndpoint, nowNanos);
            case Protocol.Operation.SET_FARE:
                return handleSetFare(request);
            case Protocol.Operation.ADD_SEATS:
                return handleAddSeats(request);
            default:
                return errorReply(
                        request, Protocol.Status.UNSUPPORTED, "Operation is not implemented");
        }
    }

    Message errorReply(Message request, int status, String description)
            throws ProtocolException {
        byte[] body = MessageCodec.bodyWriter()
                .writeU8(status)
                .writeString(description)
                .toByteArray();
        return Message.replyTo(request, body);
    }

    private Message handleRoute(Message request) throws ProtocolException {
        MessageCodec.BodyReader reader = MessageCodec.bodyReader(request.body());
        String source = normalizePlace(reader.readString());
        String destination = normalizePlace(reader.readString());
        long offset = reader.readU32();
        reader.requireFullyRead();

        if (!isValidPlace(source) || !isValidPlace(destination)) {
            return errorReply(
                    request,
                    Protocol.Status.BAD_ARGUMENT,
                    "Source and destination must occupy 1 to 255 UTF-8 bytes");
        }

        List<Integer> matches = flightService.findByRoute(source, destination);
        if (matches.isEmpty()) {
            return errorReply(request, Protocol.Status.NOT_FOUND, "No matching flights");
        }
        if (offset >= matches.size()) {
            return errorReply(request, Protocol.Status.BAD_ARGUMENT, "Route offset is out of range");
        }

        int start = (int) offset;
        int end = Math.min(start + Protocol.MAX_ROUTE_RESULTS_PER_PAGE, matches.size());
        int count = end - start;
        MessageCodec.BodyWriter writer = MessageCodec.bodyWriter()
                .writeU8(Protocol.Status.OK)
                .writeU8(end < matches.size() ? 1 : 0)
                .writeU16(count);
        for (int index = start; index < end; index++) {
            writer.writeI32(matches.get(index));
        }
        return Message.replyTo(request, writer.toByteArray());
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

    private Message handleReserve(Message request) throws ProtocolException {
        MessageCodec.BodyReader reader = MessageCodec.bodyReader(request.body());
        int flightId = reader.readI32();
        int seatCount = reader.readI32();
        reader.requireFullyRead();

        if (flightId <= 0 || seatCount <= 0) {
            return errorReply(
                    request,
                    Protocol.Status.BAD_ARGUMENT,
                    "Flight ID and seat count must be positive integers");
        }

        Flight flight = flightService.findById(flightId);
        if (flight == null) {
            return errorReply(request, Protocol.Status.NOT_FOUND, "Flight not found");
        }
        if (!flight.reserveSeats(seatCount)) {
            return errorReply(request, Protocol.Status.NO_SEATS, "Not enough seats available");
        }

        byte[] body = MessageCodec.bodyWriter()
                .writeU8(Protocol.Status.OK)
                .writeI32(flight.availableSeats())
                .toByteArray();
        return Message.replyTo(request, body);
    }

    private Message handleSetFare(Message request) throws ProtocolException {
        MessageCodec.BodyReader reader = MessageCodec.bodyReader(request.body());
        int flightId = reader.readI32();
        float newFare = reader.readFloat32();
        reader.requireFullyRead();

        if (flightId <= 0 || !Float.isFinite(newFare) || newFare < 0) {
            return errorReply(
                    request,
                    Protocol.Status.BAD_ARGUMENT,
                    "Flight ID must be positive and fare must be finite and non-negative");
        }

        Flight flight = flightService.findById(flightId);
        if (flight == null) {
            return errorReply(request, Protocol.Status.NOT_FOUND, "Flight not found");
        }

        flight.setFare(newFare);
        byte[] body = MessageCodec.bodyWriter()
                .writeU8(Protocol.Status.OK)
                .writeFloat32(flight.fare())
                .toByteArray();
        return Message.replyTo(request, body);
    }

    private Message handleMonitor(
            Message request,
            InetSocketAddress clientEndpoint,
            long nowNanos) throws ProtocolException {
        MessageCodec.BodyReader reader = MessageCodec.bodyReader(request.body());
        int flightId = reader.readI32();
        long intervalSeconds = reader.readU32();
        reader.requireFullyRead();

        if (flightId <= 0 || intervalSeconds < 1 || intervalSeconds > 600) {
            return errorReply(
                    request,
                    Protocol.Status.BAD_ARGUMENT,
                    "Flight ID must be positive and monitor interval must be 1 to 600 seconds");
        }

        Flight flight = flightService.findById(flightId);
        if (flight == null) {
            return errorReply(request, Protocol.Status.NOT_FOUND, "Flight not found");
        }

        long expiresAtNanos = monitorRegistry.register(
                flightId,
                intervalSeconds,
                request.clientId(),
                request.requestId(),
                clientEndpoint,
                nowNanos);
        long remainingMillis = MonitorRegistry.remainingMillis(expiresAtNanos, System.nanoTime());
        byte[] body = MessageCodec.bodyWriter()
                .writeU8(Protocol.Status.OK)
                .writeI32(flight.availableSeats())
                .writeU32(remainingMillis)
                .toByteArray();
        return Message.replyTo(request, body);
    }

    private Message handleAddSeats(Message request) throws ProtocolException {
        MessageCodec.BodyReader reader = MessageCodec.bodyReader(request.body());
        int flightId = reader.readI32();
        int seatCount = reader.readI32();
        reader.requireFullyRead();

        if (flightId <= 0 || seatCount <= 0) {
            return errorReply(
                    request,
                    Protocol.Status.BAD_ARGUMENT,
                    "Flight ID and seat count must be positive integers");
        }

        Flight flight = flightService.findById(flightId);
        if (flight == null) {
            return errorReply(request, Protocol.Status.NOT_FOUND, "Flight not found");
        }
        if (flight.availableSeats() > Integer.MAX_VALUE - seatCount) {
            return errorReply(request, Protocol.Status.BAD_ARGUMENT, "Seat count overflow");
        }

        flight.addSeats(seatCount);
        byte[] body = MessageCodec.bodyWriter()
                .writeU8(Protocol.Status.OK)
                .writeI32(flight.availableSeats())
                .toByteArray();
        return Message.replyTo(request, body);
    }

    private static String normalizePlace(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    }

    private static boolean isValidPlace(String value) {
        int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
        return byteLength >= 1 && byteLength <= 255;
    }
}
