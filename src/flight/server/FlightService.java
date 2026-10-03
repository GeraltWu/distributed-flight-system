package flight.server;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** 保存航班数据并提供业务查询。 */
final class FlightService {
    private final Map<Integer, Flight> flights = new HashMap<>();

    private FlightService() {
    }

    /** 创建每次启动时使用的固定内存数据。 */
    static FlightService createDefault() {
        FlightService service = new FlightService();
        service.add(new Flight(
                101,
                "Singapore",
                "Hong Kong",
                Instant.parse("2026-10-20T01:00:00Z").getEpochSecond(),
                350.0f,
                10));
        service.add(new Flight(
                102,
                "Singapore",
                "Hong Kong",
                Instant.parse("2026-10-20T05:00:00Z").getEpochSecond(),
                420.0f,
                6));
        service.add(new Flight(
                201,
                "Singapore",
                "Tokyo",
                Instant.parse("2026-10-21T00:00:00Z").getEpochSecond(),
                680.0f,
                8));
        service.add(new Flight(
                301,
                "Hong Kong",
                "Singapore",
                Instant.parse("2026-10-22T04:00:00Z").getEpochSecond(),
                390.0f,
                12));
        return service;
    }

    Flight findById(int flightId) {
        return flights.get(flightId);
    }

    private void add(Flight flight) {
        flights.put(flight.id(), flight);
    }
}
