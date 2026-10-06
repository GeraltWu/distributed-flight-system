package flight.client;

import flight.protocol.ProtocolException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

/** 客户端控制台入口。 */
public final class ClientMain {
    private static final DateTimeFormatter UTC_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                    .withZone(ZoneOffset.UTC);

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
            System.out.println("Client startup failed: " + exception.getMessage());
        }
    }

    private static void runMenu(FlightClient client) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("Enter a flight ID to view details, or 0 to exit: ");
            String input = scanner.nextLine().trim();

            int flightId;
            try {
                flightId = Integer.parseInt(input);
            } catch (NumberFormatException exception) {
                System.out.println("Please enter an integer flight ID.");
                continue;
            }

            if (flightId == 0) {
                return;
            }

            try {
                FlightClient.FlightDetails details = client.queryDetails(flightId);
                System.out.println("Departure time: "
                        + UTC_FORMAT.format(Instant.ofEpochSecond(details.departureUtcSeconds())));
                System.out.printf("Fare: %.2f%n", details.fare());
                System.out.println("Available seats: " + details.availableSeats());
            } catch (IOException | ProtocolException exception) {
                System.out.println("Query failed: " + exception.getMessage());
            }
        }
    }

    private static void printUsage() {
        System.out.println(
                "Usage: java -cp out flight.client.ClientMain <server-host> <server-port>");
    }
}
