package flight.server;

import flight.protocol.Message;
import flight.protocol.Protocol;

/** 为调用语义实验确定性地丢弃一次 ADD_SEATS 请求或回复。 */
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
    private boolean lossTriggered;

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
        if (!lossTriggered
                && mode == Mode.DROP_REQUEST_ONCE
                && request.messageType() == Protocol.MessageType.REQUEST
                && request.operation() == Protocol.Operation.ADD_SEATS) {
            lossTriggered = true;
            return true;
        }
        return false;
    }

    boolean shouldDropReply(Message reply) {
        if (!lossTriggered
                && mode == Mode.DROP_REPLY_ONCE
                && reply.messageType() == Protocol.MessageType.REPLY
                && reply.operation() == Protocol.Operation.ADD_SEATS) {
            lossTriggered = true;
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return mode.argument;
    }
}
