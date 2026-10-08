package flight.client;

import flight.protocol.ProtocolException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Scanner;

/** 客户端控制台入口。 */
public final class ClientMain {
    private static final DateTimeFormatter SINGAPORE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'SGT'")
                    .withZone(ZoneId.of("Asia/Singapore"));

    private ClientMain() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            printUsage();
            return;
        }

        InetAddress serverAddress;
        int serverPort;
        try {
            serverAddress = InetAddress.getByName(args[0]);
            serverPort = Integer.parseInt(args[1]);
            if (serverPort < 1 || serverPort > 65_535) {
                throw new NumberFormatException();
            }
        } catch (UnknownHostException | IllegalArgumentException exception) {
            printUsage();
            return;
        }

        try (FlightClient client = new FlightClient(serverAddress, serverPort)) {
            runMenu(client);
        } catch (IOException exception) {
            System.out.println("[ERROR] Client startup failed: " + exception.getMessage());
        }
    }

    private static void runMenu(FlightClient client) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            printMenu();
            String choice = scanner.nextLine().trim();
            if (choice.equals("0")) {
                return;
            }

            try {
                switch (choice) {
                    case "1":
                        queryRoute(scanner, client);
                        break;
                    case "2":
                        queryDetails(scanner, client);
                        break;
                    case "3":
                        reserveSeats(scanner, client);
                        break;
                    case "4":
                        monitorFlight(scanner, client);
                        break;
                    case "5":
                        setFare(scanner, client);
                        break;
                    case "6":
                        addSeats(scanner, client);
                        break;
                    default:
                        System.out.println("[INPUT] Unknown menu option.");
                }
            } catch (NumberFormatException exception) {
                System.out.println("[INPUT] Please enter a valid numeric value.");
            } catch (IOException | ProtocolException exception) {
                System.out.println("[ERROR] Operation failed: " + exception.getMessage());
            }
        }
    }

    private static void queryRoute(Scanner scanner, FlightClient client)
            throws IOException, ProtocolException {
        System.out.print("Source: ");
        String source = scanner.nextLine();
        System.out.print("Destination: ");
        String destination = scanner.nextLine();

        List<Integer> flightIds = client.queryRoute(source, destination);
        System.out.println("[RESULT] Matching flight IDs: " + flightIds);
    }

    private static void queryDetails(Scanner scanner, FlightClient client)
            throws IOException, ProtocolException {
        int flightId = readInt(scanner, "Flight ID: ");
        FlightClient.FlightDetails details = client.queryDetails(flightId);
        Instant departure = Instant.ofEpochSecond(details.departureUtcSeconds());
        System.out.println("[RESULT] Flight details:");
        System.out.println("  Departure time: " + SINGAPORE_FORMAT.format(departure));
        System.out.printf("  Fare: %.2f%n", details.fare());
        System.out.println("  Available seats: " + details.availableSeats());
    }

    private static void reserveSeats(Scanner scanner, FlightClient client)
            throws IOException, ProtocolException {
        int flightId = readInt(scanner, "Flight ID: ");
        int seatCount = readInt(scanner, "Seats to reserve: ");
        int availableSeats = client.reserveSeats(flightId, seatCount);
        System.out.println("[RESULT] Reservation completed. Available seats: " + availableSeats);
    }

    private static void setFare(Scanner scanner, FlightClient client)
            throws IOException, ProtocolException {
        int flightId = readInt(scanner, "Flight ID: ");
        float newFare = readFloat(scanner, "New fare: ");
        float currentFare = client.setFare(flightId, newFare);
        System.out.printf("[RESULT] Fare updated: %.2f%n", currentFare);
    }

    private static void monitorFlight(Scanner scanner, FlightClient client)
            throws IOException, ProtocolException {
        int flightId = readInt(scanner, "Flight ID: ");
        long intervalSeconds = readLong(scanner, "Monitor interval in seconds (1-600): ");
        client.monitorFlight(
                flightId,
                intervalSeconds,
                registration -> System.out.printf(
                        "[RESULT] Monitor registered. Available seats: %d; remaining: %d ms%n",
                        registration.availableSeats(),
                        registration.remainingMillis()),
                event -> {
                    Instant eventTime = Instant.ofEpochMilli(event.eventUtcMillis());
                    System.out.printf(
                            "[EVENT] Flight %d seats=%d, sequence=%d, time=%s%n",
                            event.flightId(),
                            event.availableSeats(),
                            event.eventSequence(),
                            SINGAPORE_FORMAT.format(eventTime));
                });
        System.out.println("[RESULT] Monitor period ended.");
    }

    private static void addSeats(Scanner scanner, FlightClient client)
            throws IOException, ProtocolException {
        int flightId = readInt(scanner, "Flight ID: ");
        int seatCount = readInt(scanner, "Seats to add: ");
        int availableSeats = client.addSeats(flightId, seatCount);
        System.out.println("[RESULT] Seats added. Available seats: " + availableSeats);
    }

    private static int readInt(Scanner scanner, String prompt) {
        System.out.print(prompt);
        return Integer.parseInt(scanner.nextLine().trim());
    }

    private static float readFloat(Scanner scanner, String prompt) {
        System.out.print(prompt);
        return Float.parseFloat(scanner.nextLine().trim());
    }

    private static long readLong(Scanner scanner, String prompt) {
        System.out.print(prompt);
        return Long.parseLong(scanner.nextLine().trim());
    }

    private static void printMenu() {
        System.out.println();
        System.out.println("=== Flight Operations ===");
        System.out.println("1. Find flights by route");
        System.out.println("2. View flight details");
        System.out.println("3. Reserve seats");
        System.out.println("4. Monitor seat availability");
        System.out.println("5. Set fare");
        System.out.println("6. Add seats");
        System.out.println("0. Exit");
        System.out.print("Select an option: ");
    }

    private static void printUsage() {
        System.out.println(
                "[USAGE] java -cp out flight.client.ClientMain <server-host> <server-port>");
    }
}
