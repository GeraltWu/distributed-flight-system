package flight.server;

/** 服务端内存中的一条航班记录。 */
final class Flight {
    private final int id;
    private final String source;
    private final String destination;
    private final long departureUtcSeconds;
    private float fare;
    private int availableSeats;

    Flight(
            int id,
            String source,
            String destination,
            long departureUtcSeconds,
            float fare,
            int availableSeats) {
        this.id = id;
        this.source = source;
        this.destination = destination;
        this.departureUtcSeconds = departureUtcSeconds;
        this.fare = fare;
        this.availableSeats = availableSeats;
    }

    int id() {
        return id;
    }

    String source() {
        return source;
    }

    String destination() {
        return destination;
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
