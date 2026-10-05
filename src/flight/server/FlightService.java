package flight.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 保存航班数据并提供业务查询。 */
final class FlightService {
    private final Map<Integer, Flight> flights = new HashMap<>();

    private FlightService() {
    }

    /** 从 TSV 文件读取初始数据，运行期间仍在内存中维护航班。 */
    static FlightService load(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()
                || !lines.get(0).equals("id\tsource\tdestination\tdepartureUtc\tfare\tavailableSeats")) {
            throw new IOException("航班数据缺少正确的表头");
        }

        FlightService service = new FlightService();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }

            int lineNumber = index + 1;
            String[] fields = line.split("\t", -1);
            if (fields.length != 6) {
                throw invalidLine(lineNumber, "应有 6 个字段");
            }

            try {
                int id = Integer.parseInt(fields[0]);
                String source = fields[1];
                String destination = fields[2];
                long departure = Instant.parse(fields[3]).getEpochSecond();
                float fare = Float.parseFloat(fields[4]);
                int availableSeats = Integer.parseInt(fields[5]);

                // 这些限制与协议的业务字段范围一致，避免载入无法正常传输的数据。
                if (id <= 0) {
                    throw invalidLine(lineNumber, "航班号必须为正数");
                }
                validatePlace(source, "出发地", lineNumber);
                validatePlace(destination, "目的地", lineNumber);
                if (!Float.isFinite(fare) || fare < 0) {
                    throw invalidLine(lineNumber, "票价必须是有限的非负数");
                }
                if (availableSeats < 0) {
                    throw invalidLine(lineNumber, "剩余座位不能为负数");
                }

                Flight flight = new Flight(id, source, destination, departure, fare, availableSeats);
                if (service.flights.putIfAbsent(id, flight) != null) {
                    throw invalidLine(lineNumber, "航班号重复：" + id);
                }
            } catch (NumberFormatException | java.time.format.DateTimeParseException exception) {
                throw invalidLine(lineNumber, "数字或时间格式错误");
            }
        }

        if (service.flights.isEmpty()) {
            throw new IOException("航班数据中没有记录");
        }
        return service;
    }

    Flight findById(int flightId) {
        return flights.get(flightId);
    }

    private static void validatePlace(String value, String name, int lineNumber) throws IOException {
        int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength < 1 || byteLength > 255) {
            throw invalidLine(lineNumber, name + "必须占 1 至 255 个 UTF-8 字节");
        }
    }

    private static IOException invalidLine(int lineNumber, String reason) {
        return new IOException("航班数据第 " + lineNumber + " 行无效：" + reason);
    }
}
