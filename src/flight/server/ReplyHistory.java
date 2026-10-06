package flight.server;

import flight.protocol.Message;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** 保存至多一次模式所需的最高请求编号和最近一次回复。 */
final class ReplyHistory {
    private static final long REPLY_TTL_NANOS = 60_000_000_000L;

    enum Decision {
        NEW_REQUEST,
        REPLAY_CACHED,
        CONFLICT,
        STALE
    }

    private final Map<Long, ClientState> clients = new HashMap<>();

    Decision classify(Message request, long nowNanos) {
        ClientState state = clients.get(request.clientId());
        if (state == null) {
            return Decision.NEW_REQUEST;
        }

        int order = Long.compareUnsigned(request.requestId(), state.highestRequestId);
        if (order > 0) {
            return Decision.NEW_REQUEST;
        }
        if (order < 0) {
            return Decision.STALE;
        }

        // 最高编号永久保留；缓存被确认或过期后，同编号请求只能判为旧请求。
        if (state.reply == null || nowNanos >= state.expiresAtNanos) {
            state.clearReply();
            return Decision.STALE;
        }
        if (request.operation() != state.operation
                || !Arrays.equals(request.body(), state.requestBody)) {
            return Decision.CONFLICT;
        }
        return Decision.REPLAY_CACHED;
    }

    void remember(Message request, Message reply, long nowNanos) {
        ClientState state = clients.get(request.clientId());
        if (state == null) {
            state = new ClientState();
            clients.put(request.clientId(), state);
        }

        state.highestRequestId = request.requestId();
        state.operation = request.operation();
        state.requestBody = request.body();
        state.reply = reply;
        state.expiresAtNanos = nowNanos + REPLY_TTL_NANOS;
    }

    Message cachedReply(long clientId) {
        return clients.get(clientId).reply;
    }

    boolean acknowledge(Message acknowledgement) {
        ClientState state = clients.get(acknowledgement.clientId());
        if (state == null
                || state.reply == null
                || state.highestRequestId != acknowledgement.requestId()
                || state.operation != acknowledgement.operation()) {
            return false;
        }

        state.clearReply();
        return true;
    }

    void removeExpiredReplies(long nowNanos) {
        for (ClientState state : clients.values()) {
            if (state.reply != null && nowNanos >= state.expiresAtNanos) {
                state.clearReply();
            }
        }
    }

    private static final class ClientState {
        private long highestRequestId;
        private int operation;
        private byte[] requestBody;
        private Message reply;
        private long expiresAtNanos;

        private void clearReply() {
            requestBody = null;
            reply = null;
            expiresAtNanos = 0;
        }
    }
}
