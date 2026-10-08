package flight.server;

import flight.protocol.Message;
import flight.protocol.MessageCodec;
import flight.protocol.ProtocolException;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 保存 MONITOR 登记，并为座位变化生成回调事件。 */
final class MonitorRegistry {
    private final Map<RegistrationKey, Registration> registrations = new HashMap<>();

    long register(
            int flightId,
            long intervalSeconds,
            long clientId,
            long requestId,
            InetSocketAddress endpoint,
            long nowNanos) {
        long expiresAtNanos = nowNanos + TimeUnit.SECONDS.toNanos(intervalSeconds);
        RegistrationKey key = new RegistrationKey(clientId, requestId);
        Registration previous = registrations.get(key);
        long eventSequence = previous == null ? 0 : previous.eventSequence;
        registrations.put(
                key,
                new Registration(flightId, endpoint, expiresAtNanos, eventSequence));
        return expiresAtNanos;
    }

    long expiryFor(long clientId, long requestId) {
        Registration registration = registrations.get(new RegistrationKey(clientId, requestId));
        return registration == null ? 0 : registration.expiresAtNanos;
    }

    List<OutboundEvent> createSeatEvents(
            int flightId,
            int availableSeats,
            long nowNanos,
            long eventUtcMillis) throws ProtocolException {
        List<OutboundEvent> events = new ArrayList<>();
        Iterator<Map.Entry<RegistrationKey, Registration>> iterator =
                registrations.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<RegistrationKey, Registration> entry = iterator.next();
            Registration registration = entry.getValue();
            if (registration.expiresAtNanos - nowNanos <= 0) {
                iterator.remove();
                continue;
            }
            if (registration.flightId != flightId) {
                continue;
            }

            registration.eventSequence++;
            if (registration.eventSequence > 0xffff_ffffL) {
                registration.eventSequence = 1;
            }

            byte[] body = MessageCodec.bodyWriter()
                    .writeI32(flightId)
                    .writeI32(availableSeats)
                    .writeU32(registration.eventSequence)
                    .writeI64(eventUtcMillis)
                    .toByteArray();
            Message event = Message.monitorEvent(
                    entry.getKey().clientId,
                    entry.getKey().requestId,
                    body);
            events.add(new OutboundEvent(event, registration.endpoint));
        }
        return events;
    }

    void removeExpired(long nowNanos) {
        registrations.values().removeIf(
                registration -> registration.expiresAtNanos - nowNanos <= 0);
    }

    static long remainingMillis(long expiresAtNanos, long nowNanos) {
        long remainingNanos = expiresAtNanos - nowNanos;
        if (remainingNanos <= 0) {
            return 0;
        }
        return (remainingNanos + 999_999L) / 1_000_000L;
    }

    static final class OutboundEvent {
        private final Message message;
        private final InetSocketAddress endpoint;

        private OutboundEvent(Message message, InetSocketAddress endpoint) {
            this.message = message;
            this.endpoint = endpoint;
        }

        Message message() {
            return message;
        }

        InetSocketAddress endpoint() {
            return endpoint;
        }
    }

    private static final class RegistrationKey {
        private final long clientId;
        private final long requestId;

        private RegistrationKey(long clientId, long requestId) {
            this.clientId = clientId;
            this.requestId = requestId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RegistrationKey)) {
                return false;
            }
            RegistrationKey key = (RegistrationKey) other;
            return clientId == key.clientId && requestId == key.requestId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(clientId, requestId);
        }
    }

    private static final class Registration {
        private final int flightId;
        private final InetSocketAddress endpoint;
        private final long expiresAtNanos;
        private long eventSequence;

        private Registration(
                int flightId,
                InetSocketAddress endpoint,
                long expiresAtNanos,
                long eventSequence) {
            this.flightId = flightId;
            this.endpoint = endpoint;
            this.expiresAtNanos = expiresAtNanos;
            this.eventSequence = eventSequence;
        }
    }
}
