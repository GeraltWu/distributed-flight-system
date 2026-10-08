package flight.server;

import flight.protocol.Message;
import flight.protocol.Protocol;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 为每个 ADD_SEATS 调用确定性地丢弃它的第一次请求或第一次回复。 */
final class LossSimulator {
    private enum Mode {
        NONE("none"),
        DROP_REQUEST_ONCE("drop-request-once"),
        DROP_REPLY_ONCE("drop-reply-once");

        private final String argument;

        Mode(String argument) {
            this.argument = argument;
        }
    }

    private final Mode mode;
    private final Set<RequestKey> triggeredRequests = new HashSet<>();

    private LossSimulator(Mode mode) {
        this.mode = mode;
    }

    static LossSimulator none() {
        return new LossSimulator(Mode.NONE);
    }

    static LossSimulator parse(String value) {
        for (Mode mode : Mode.values()) {
            if (mode.argument.equals(value)) {
                return new LossSimulator(mode);
            }
        }
        throw new IllegalArgumentException("Unknown loss simulation: " + value);
    }

    boolean shouldDropRequest(Message request) {
        if (mode == Mode.DROP_REQUEST_ONCE
                && request.messageType() == Protocol.MessageType.REQUEST
                && request.operation() == Protocol.Operation.ADD_SEATS) {
            return triggeredRequests.add(RequestKey.from(request));
        }
        return false;
    }

    boolean shouldDropReply(Message reply) {
        if (mode == Mode.DROP_REPLY_ONCE
                && reply.messageType() == Protocol.MessageType.REPLY
                && reply.operation() == Protocol.Operation.ADD_SEATS) {
            return triggeredRequests.add(RequestKey.from(reply));
        }
        return false;
    }

    @Override
    public String toString() {
        return mode.argument;
    }

    private static final class RequestKey {
        private final long clientId;
        private final long requestId;

        private RequestKey(long clientId, long requestId) {
            this.clientId = clientId;
            this.requestId = requestId;
        }

        private static RequestKey from(Message message) {
            return new RequestKey(message.clientId(), message.requestId());
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RequestKey)) {
                return false;
            }
            RequestKey key = (RequestKey) other;
            return clientId == key.clientId && requestId == key.requestId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(clientId, requestId);
        }
    }
}
